package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.AppealRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.feed.FeedTimelineStore;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.gateway.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 媒体审核服务：维护内容审核状态机，并执行「机审初筛 + 账号信用分级（先发/先审）+ 多池发布 + 举报/申诉闭环」。
 *
 * <p><b>抖音式分层</b>：上传后先跑机审初筛（{@link ContentModerationRouter}，MVP=本地规则引擎 RULE）；
 * 机审通过的内容按<b>账号信用等级</b>分流——L0 低信用/新号<b>先审后放</b>（进 PENDING 等人审），
 * L1/L2 高信用<b>先发后审</b>（直接 APPROVED 进对应流量池，靠举报/人审兜底）；机审 REJECTED 立即拦截。
 * 已发布内容可被用户举报、作者申诉，形成完整治理闭环。</p>
 *
 * <p><b>存储边界</b>：审核状态已落库（{@link MediaJdbcRepository}，media 表 status 列），重启不丢失；
 * 公域可见性由 {@link FeedTimelineStore}（多池 ZSET）物化，发布即按信用进对应池。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaReviewService {

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModerationRouter contentModeration;
    private final FeedTimelineStore feedTimelineStore;
    private final MediaProperties properties;
    private final AccountCreditService accountCreditService;
    private final ReportRepository reportRepository;
    private final AppealRepository appealRepository;

    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /**
     * 上传事件处理入口（本地 Spring 事件与 RocketMQ 消费者共用，审核逻辑单一来源）。
     *
     * <p>流程：落库 PENDING → 机审初筛 → 按账号信用分级决定先发后审 / 先审后放。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        MediaStatus existing = mediaRepository.getStatus(event.mediaId(), userId);
        if (existing != null && existing != MediaStatus.PENDING) {
            // 重复投递：已是终态，幂等返回；APPROVED 兜底补齐时间线（按信用池）
            if (existing == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelineStore.append(
                        new MediaItem(event.mediaId(), event.url(), MediaStatus.APPROVED, event.occurredAt()),
                        level.poolLevel());
            }
            return;
        }
        // 首投：落库受理态
        mediaRepository.insert(event.mediaId(), userId, event.url(), MediaStatus.PENDING, event.occurredAt());

        // —— 机审初筛（始终执行）——
        MediaStatus machine = contentModeration.moderate(event.mediaId(), userId, event.url());

        if (properties.getReview().isAutoPass()) {
            // 演示占位：机审结果直接放行（仅供本地联调）
            MediaStatus target = review(event.mediaId(), userId, machine == MediaStatus.APPROVED);
            if (target == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelineStore.append(
                        new MediaItem(event.mediaId(), event.url(), MediaStatus.APPROVED, event.occurredAt()),
                        level.poolLevel());
            }
            return;
        }

        if (machine == MediaStatus.REJECTED) {
            // 机审驳回即拦：直接翻 REJECTED，不进人工队列
            review(event.mediaId(), userId, false);
            log.info("机审驳回即拦：内容直接判定 REJECTED（不进人审队列）: mediaId={}, userId={}", event.mediaId(), userId);
            return;
        }

        // 机审通过/降级：按账号信用分级分流
        CreditLevel level = accountCreditService.ensure(userId);
        if (level == CreditLevel.L0) {
            // 先审后放：机审通过仍进 PENDING，等人审终裁（不自动进公域）
            log.info("低信用账户：机审通过转人审队列（先审后放）: mediaId={}, userId={}", event.mediaId(), userId);
            return;
        }
        // 先发后审（L1/L2）：直接 APPROVED 进对应流量池（小池/大池），靠举报/人审兜底
        MediaStatus target = review(event.mediaId(), userId, true);
        if (target == MediaStatus.APPROVED) {
            feedTimelineStore.append(
                    new MediaItem(event.mediaId(), event.url(), MediaStatus.APPROVED, event.occurredAt()),
                    level.poolLevel());
        }
    }

    /**
     * 执行审核动作：PENDING -> APPROVED / REJECTED（终态）。
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
        evictCache(mediaId);
        log.info("审核状态流转: mediaId={}, {} -> {}", mediaId, current, target);
        return target;
    }

    /**
     * 管理员审核入口：PENDING → APPROVED / REJECTED，通过即按信用进对应流量池。
     */
    public MediaStatus reviewByMediaId(String mediaId, boolean approved) {
        long userId = parseUserId(mediaId);
        MediaStatus target = review(mediaId, userId, approved);
        if (target == MediaStatus.APPROVED) {
            MediaItem item = mediaRepository.findMedia(mediaId, userId);
            if (item != null) {
                CreditLevel level = accountCreditService.ensure(userId);
                feedTimelineStore.append(
                        new MediaItem(item.mediaId(), item.url(), MediaStatus.APPROVED, item.createdAt()),
                        level.poolLevel());
            }
        }
        return target;
    }

    /**
     * 用户举报：仅已发布内容可被举报。高危类目（涉政/暴恐/儿童）立即下架停推（fail-closed）；
     * 普通举报写表进人工队列，由 {@link #handleReport} 复核。
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
            feedTimelineStore.remove(mediaId);
            accountCreditService.onViolationConfirmed(authorId);
            log.warn("高危举报立即下架停推: mediaId={}, reporterUserId={}, reason={}", mediaId, reporterUserId, reason);
        }
        // 普通举报：进人工队列，管理员 report-review 处理
    }

    /** 管理员处理举报：确认违规→TAKEN_DOWN + 扣信用；驳回→内容保持。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleReport(String mediaId, boolean confirmed) {
        long authorId = parseUserId(mediaId);
        if (confirmed) {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.TAKEN_DOWN);
            feedTimelineStore.remove(mediaId);
            accountCreditService.onViolationConfirmed(authorId);
            log.info("举报确认违规→下架: mediaId={}", mediaId);
        }
        reportRepository.resolve(mediaId, confirmed);
    }

    /**
     * 作者申诉：仅被驳回/下架内容可申。状态翻 APPEALING（暂不可见），写表进人工复核。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(String mediaId, long authorUserId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorUserId);
        if (cur == null || (cur != MediaStatus.REJECTED && cur != MediaStatus.TAKEN_DOWN)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅被驳回/下架内容可申诉");
        }
        mediaRepository.updateStatus(mediaId, authorUserId, MediaStatus.APPEALING);
        feedTimelineStore.remove(mediaId); // 申诉中暂不可见
        appealRepository.insert(mediaId, authorUserId);
        log.info("作者申诉（内容暂不可见）: mediaId={}, authorUserId={}", mediaId, authorUserId);
    }

    /** 管理员处理申诉：翻案→APPROVED 恢复公域（按信用池）+ 信用加回；维持→TAKEN_DOWN。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(String mediaId, boolean upheld) {
        long authorId = parseUserId(mediaId);
        if (upheld) {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.APPROVED);
            MediaItem item = mediaRepository.findMedia(mediaId, authorId);
            if (item != null) {
                CreditLevel level = accountCreditService.ensure(authorId);
                feedTimelineStore.append(
                        new MediaItem(item.mediaId(), item.url(), MediaStatus.APPROVED, item.createdAt()),
                        level.poolLevel());
            }
            accountCreditService.onAppealUpheld(authorId);
            log.info("申诉翻案→恢复公域: mediaId={}", mediaId);
        } else {
            mediaRepository.updateStatus(mediaId, authorId, MediaStatus.TAKEN_DOWN);
            log.info("申诉维持原状: mediaId={}", mediaId);
        }
        appealRepository.resolve(mediaId, upheld);
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

    /** 失效单条状态缓存（旁路缓存 fail-open）。 */
    private void evictCache(String mediaId) {
        try {
            redisTemplate.delete(STATUS_KEY_PREFIX + mediaId);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }
}
