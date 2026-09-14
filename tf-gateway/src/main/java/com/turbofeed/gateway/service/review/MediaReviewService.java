package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.AppealRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 媒体审核服务：维护内容审核状态机，并执行「机审初筛 + 账号信用分级（先发/先审）+ 多池发布 + 举报/申诉闭环」。
 *
 * <p><b>抖音式分层</b>：上传后先跑机审初筛（{@link ContentModerationRouter}，MVP=本地规则引擎 RULE）；
 * 机审通过的内容按<b>账号信用等级</b>分流——L0 低信用/新号<b>先审后放</b>（进 PENDING 等人审），
 * L1/L2 高信用<b>先发后审</b>（直接 APPROVED 进对应流量池，靠举报/人审兜底）；机审 REJECTED 立即拦截。
 * 已发布内容可被用户举报、作者申诉，形成完整治理闭环。</p>
 *
 * <h3>整帖一审（一帖多图）</h3>
 * <p>一次上传批次的 N 张图构成一个<b>帖子</b>（{@code post_id}），审核以<b>帖</b>为单位：
 * 机审一次、状态一次翻转、公域时间线一次投递。<b>整帖同上同下</b>——任一图被驳回即整帖驳回，
 * 下架/申诉也作用在整帖上。这不是实现便利，而是内容安全的必要条件：若允许「一帖内一半可见」，
 * 同一条帖子在不同入口会呈现互相矛盾的可见性。</p>
 *
 * <p><b>操作对象统一为「帖代表媒体 ID」</b>：{@code media_id} 是 media 表主键、{@code post_id} 不是，
 * 因此外部接口（含管理端）仍以 {@code mediaId} 为参数，本服务负责把它解析到帖子并落到整帖；
 * 这是「接口签名不变、语义升级为整帖」的关键，避免前端与管理端全部改签名。</p>
 *
 * <p><b>存储边界</b>：审核状态已落库（{@link MediaJdbcRepository}，media 表 status 列），重启不丢失；
 * 公域可见性由 tf-feed-engine 的时间线读模型物化（多池 ZSET），发布即按信用进对应池。
 * 本服务通过 {@link FeedTimelinePublisher} 投递入流/下架（mq 关闭时走同步 HTTP 兜底、开启时走
 * RocketMQ 顺序消息且 fail-fast，由 MQ 重试/DLQ 兜底）：可见性是审核的下一跳，仍不能让引擎抖动
 * 反向阻断审核状态机本身（mq 模式下靠 @Transactional 回滚保证不丢）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaReviewService {

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModerationRouter contentModeration;
    private final FeedTimelinePublisher feedTimelinePublisher;
    private final MediaProperties properties;
    private final AccountCreditService accountCreditService;
    private final ReportRepository reportRepository;
    private final AppealRepository appealRepository;

    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /**
     * 上传事件处理入口（本地 Spring 事件与 RocketMQ 消费者共用，审核逻辑单一来源）。
     *
     * <p>流程：整帖落库 PENDING → 机审初筛（整帖一次）→ 按账号信用分级决定先发后审 / 先审后放。</p>
     *
     * <p><b>为什么这里要兜底落库整帖</b>：正常路径由上传服务先同步落库（消灭「存储成功但事件丢失」
     * 的孤儿对象），本方法再以 {@code media_id} 主键幂等重写一遍。事件重投（MQ 重试 / DLQ 后人工重放）
     * 时，若上传服务那一步曾被回滚，这里的兜底插入能把整帖补齐，避免「状态已流转但行不存在」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        String representativeId = event.representativeMediaId();
        if (representativeId == null) {
            // 空帖事件（不该出现）：不抛异常，避免一条坏消息把 MQ 拖进无意义的重试/DLQ 循环
            log.warn("收到不含任何媒体的上传事件，忽略: postId={}, userId={}", event.postId(), userId);
            return;
        }

        MediaStatus existing = mediaRepository.getStatus(representativeId, userId);
        if (existing != null && existing != MediaStatus.PENDING) {
            // 重复投递：已是终态，幂等返回；APPROVED 兜底补齐时间线（按信用池）
            if (existing == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelinePublisher.append(toTimelinePost(event), level.poolLevel());
            }
            return;
        }

        // 兜底落库整帖（命中主键即幂等更新；seq 取下标，与上传服务写入的顺序一致）
        List<String> mediaIds = event.mediaIds();
        List<String> urls = event.urls();
        for (int i = 0; i < mediaIds.size(); i++) {
            mediaRepository.insert(event.postId(), mediaIds.get(i), userId, urls.get(i),
                    MediaStatus.PENDING, event.caption(), event.captionMark(), i, event.occurredAt());
        }

        // —— 机审初筛（整帖一次；以首图 url 作为判定输入）——
        MediaStatus machine = contentModeration.moderate(representativeId, userId, urls.get(0));

        if (properties.getReview().isAutoPass()) {
            // 演示占位：机审结果直接放行（仅供本地联调）
            MediaStatus target = review(representativeId, userId, machine == MediaStatus.APPROVED);
            if (target == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelinePublisher.append(toTimelinePost(event), level.poolLevel());
            }
            return;
        }

        if (machine == MediaStatus.REJECTED) {
            // 机审驳回即拦：整帖翻 REJECTED，不进人工队列
            review(representativeId, userId, false);
            log.info("机审驳回即拦：整帖判定 REJECTED（不进人审队列）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }

        // 机审通过/降级：按账号信用分级分流
        CreditLevel level = accountCreditService.ensure(userId);
        if (level == CreditLevel.L0) {
            // 先审后放：机审通过仍进 PENDING，等人审终裁（不自动进公域）
            log.info("低信用账户：机审通过转人审队列（先审后放，整帖）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }
        // 先发后审（L1/L2）：整帖直接 APPROVED 进对应流量池（小池/大池），靠举报/人审兜底
        MediaStatus target = review(representativeId, userId, true);
        if (target == MediaStatus.APPROVED) {
            feedTimelinePublisher.append(toTimelinePost(event), level.poolLevel());
        }
    }

    /**
     * 执行审核动作：PENDING -> APPROVED / REJECTED（终态），<b>作用于整帖</b>。
     *
     * <p>{@code mediaId} 用于定位帖子；仓库层的 {@code updateStatus} 会把状态落到帖内全部行。</p>
     */
    public MediaStatus review(String mediaId, long userId, boolean approved) {
        MediaStatus current = mediaRepository.getStatus(mediaId, userId);
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未找到待审核记录: mediaId=" + mediaId);
        }
        if (current != MediaStatus.PENDING) {
            log.info("审核终态幂等返回（不重复流转）: mediaId={}, status={}", mediaId, current);
            return current;
        }
        MediaStatus target = approved ? MediaStatus.APPROVED : MediaStatus.REJECTED;
        mediaRepository.updateStatus(mediaId, userId, target);
        evictCache(mediaId, userId);
        log.info("审核状态流转（整帖）: mediaId={}, {} -> {}", mediaId, current, target);
        return target;
    }

    /**
     * 管理员审核入口：PENDING → APPROVED / REJECTED，通过即按信用把<b>整帖</b>放进对应流量池。
     */
    public MediaStatus reviewByMediaId(String mediaId, boolean approved) {
        long userId = parseUserId(mediaId);
        MediaStatus target = review(mediaId, userId, approved);
        if (target == MediaStatus.APPROVED) {
            MediaItem post = asTimelinePost(mediaRepository.findMedia(mediaId, userId), userId);
            if (post != null) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelinePublisher.append(post, level.poolLevel());
            }
        }
        return target;
    }

    /**
     * 用户举报：仅已发布内容可被举报。高危类目（涉政/暴恐/儿童）立即下架停推（fail-closed）；
     * 普通举报写表进人工队列，由 {@link #handleReport} 复核。
     *
     * <p>下架作用于<b>整帖</b>（帖内任一图被举报即整帖不可见），避免「举报了 9 张里的 1 张，
     * 其余 8 张继续可见」的绕过路径。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void report(String mediaId, long reporterUserId, String reason) {
        long authorId = parseUserId(mediaId);
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur == null || cur != MediaStatus.APPROVED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅已发布内容可被举报");
        }
        reportRepository.insert(mediaId, reporterUserId, reason);
        if (isHighRisk(reason)) {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.TAKEN_DOWN);
            removeFromTimeline(mediaId, authorId);
            accountCreditService.onViolationConfirmed(authorId);
            log.warn("高危举报立即下架停推（整帖）: mediaId={}, reporterUserId={}, reason={}",
                    mediaId, reporterUserId, reason);
        }
        // 普通举报：进人工队列，管理员 report-review 处理
    }

    /** 管理员处理举报：确认违规→整帖 TAKEN_DOWN + 扣信用；驳回→内容保持。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleReport(String mediaId, boolean confirmed) {
        long authorId = parseUserId(mediaId);
        if (confirmed) {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.TAKEN_DOWN);
            removeFromTimeline(mediaId, authorId);
            accountCreditService.onViolationConfirmed(authorId);
            log.info("举报确认违规→整帖下架: mediaId={}", mediaId);
        }
        reportRepository.resolve(mediaId, confirmed);
    }

    /**
     * 作者申诉：仅被驳回/下架内容可申。整帖翻 APPEALING（暂不可见），写表进人工复核。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(String mediaId, long authorUserId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorUserId);
        if (cur == null || (cur != MediaStatus.REJECTED && cur != MediaStatus.TAKEN_DOWN)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅被驳回/下架内容可申诉");
        }
        mediaRepository.updateStatus(mediaId, authorUserId, MediaStatus.APPEALING);
        removeFromTimeline(mediaId, authorUserId); // 申诉中暂不可见
        appealRepository.insert(mediaId, authorUserId);
        log.info("作者申诉（整帖暂不可见）: mediaId={}, authorUserId={}", mediaId, authorUserId);
    }

    /** 管理员处理申诉：翻案→整帖 APPROVED 恢复公域（按信用池）+ 信用加回；维持→整帖 TAKEN_DOWN。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(String mediaId, boolean upheld) {
        long authorId = parseUserId(mediaId);
        if (upheld) {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.APPROVED);
            MediaItem post = asTimelinePost(mediaRepository.findMedia(mediaId, authorId), authorId);
            if (post != null) {
                CreditLevel level = accountCreditService.ensure(authorId);
                feedTimelinePublisher.append(post, level.poolLevel());
            }
            accountCreditService.onAppealUpheld(authorId);
            log.info("申诉翻案→整帖恢复公域: mediaId={}", mediaId);
        } else {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.TAKEN_DOWN);
            log.info("申诉维持原状: mediaId={}", mediaId);
        }
        appealRepository.resolve(mediaId, upheld);
    }

    /**
     * 事件 → 公域时间线条目（物化进 Redis ZSET 的 {@code FeedItemView} JSON，含描述/标题与整帖图片）。
     *
     * <p>描述与图片列表随事件透传而非回查 DB：审核发布是热路径，避免为拿 caption 与 images
     * 多两轮分片查询；事件与首落库携带同一份数据，物化结果与库中一致，Feed 直接可展示。</p>
     */
    private static MediaItem toTimelinePost(MediaUploadedEvent event) {
        String representativeId = event.representativeMediaId();
        String cover = event.urls().isEmpty() ? null : event.urls().get(0);
        return new MediaItem(event.postId(), representativeId, cover, event.urls(), 0,
                MediaStatus.APPROVED, event.occurredAt(), event.caption(), event.captionMark());
    }

    /**
     * 把<b>行级</b>条目升级为可入流的<b>整帖</b>条目：补齐帖内全部图片（按 seq 升序）。
     *
     * <p>{@code findMedia} 返回的行级视图 {@code images} 只含自身，直接入流会让一帖九图在
     * 公域只显示一张。历史单图帖（{@code post_id} 为空）天然只有一张，走单元素列表。</p>
     */
    private MediaItem asTimelinePost(MediaItem row, long userId) {
        if (row == null) {
            return null;
        }
        List<String> images;
        if (row.postId() == null || row.postId().isBlank()) {
            images = row.url() == null ? List.of() : List.of(row.url());
        } else {
            List<MediaItem> rows = mediaRepository.listPostImages(userId, row.postId());
            images = new ArrayList<>(rows.size());
            for (MediaItem r : rows) {
                if (r.url() != null) {
                    images.add(r.url());
                }
            }
            if (images.isEmpty()) {
                images = row.url() == null ? List.of() : List.of(row.url());
            }
        }
        return new MediaItem(row.postId(), row.mediaId(), row.url(), images, 0,
                MediaStatus.APPROVED, row.createdAt(), row.caption(), row.captionMark());
    }

    /**
     * 把帖从公域时间线摘除：<b>必须用与投递时相同的键</b>（有 postId 用 postId，
     * 历史单图数据回退 mediaId），否则新旧数据会落在两个反查索引上，下架失效。
     */
    private void removeFromTimeline(String mediaId, long userId) {
        String postId = mediaRepository.findPostId(mediaId, userId);
        String key = postId == null || postId.isBlank() ? mediaId : postId;
        feedTimelinePublisher.remove(key);
    }

    private static boolean isHighRisk(String reason) {
        if (reason == null) return false;
        return reason.contains("涉政") || reason.contains("暴恐")
                || reason.contains("儿童") || reason.contains("未成年") || reason.contains("色情儿童");
    }

    /** 从 mediaId（media/{userId}/{uuid}.{ext}）解析归属 userId，用于审核接口分片路由。 */
    private static long parseUserId(String mediaId) {
        String[] parts = mediaId.split("/");
        if (parts.length < 2) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的 mediaId: " + mediaId);
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mediaId 中的 userId 非法: " + mediaId);
        }
    }

    /**
     * 失效<b>整帖</b>的状态缓存（旁路缓存 fail-open）。
     *
     * <p>状态缓存按 {@code mediaId} 分键（{@code tf:media:status:{mediaId}}），而状态翻转是整帖的：
     * 只清代表行会让同帖其余行在 60s TTL 内继续返回旧状态——「我的内容」若按任意行读状态就可能
     * 显示「审核中」而公域已可见。故这里把帖内全部 mediaId 的缓存一并清掉。</p>
     */
    private void evictCache(String mediaId, long userId) {
        try {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(STATUS_KEY_PREFIX + mediaId);
            String postId = mediaRepository.findPostId(mediaId, userId);
            if (postId != null && !postId.isBlank()) {
                for (MediaItem row : mediaRepository.listPostImages(userId, postId)) {
                    keys.add(STATUS_KEY_PREFIX + row.mediaId());
                }
            }
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }
}
