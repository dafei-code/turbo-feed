package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.feed.FeedTimelineStore;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 媒体审核服务：维护内容审核状态，执行唯一的合法转换 PENDING -&gt; APPROVED / REJECTED。
 *
 * <p><b>存储边界</b>：审核状态已落库（{@link MediaJdbcRepository}，media 表 status 列），
 * 不再依赖进程内内存态（原 statusRegistry 已退役），重启不丢失。状态与内容元数据同表，
 * 单一事实源，无需双写。</p>
 *
 * <p><b>缓存一致性（P2）</b>：审核状态写入后精确失效单条状态缓存
 * （{@code tf:media:status:{mediaId}}），保证个人中心状态查询立即刷新；公域推荐流
 * 采用短 TTL（15s）最终一致，不在此主动批量失效（避免 Redis keys 阻塞，详见 changelog 0018）。</p>
 *
 * <p>状态约束：仅 PENDING 可转终态（APPROVED / REJECTED），终态不可再流转——这一条
 * 合法性检查落在 {@link #review} 内（单路径转换无需独立状态机）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaReviewService {

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModeration contentModeration;
    private final FeedTimelineStore feedTimelineStore;
    private final MediaProperties properties;

    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /**
     * 上传事件处理入口（Observer 两种事件源共用）：
     * {@link com.turbofeed.gateway.service.event.ReviewListener}（本地 Spring 事件）与
     * {@code MediaReviewConsumer}（RocketMQ）均调用本方法，保证审核逻辑单一来源。
     *
     * <p>流程：先落库受理态 PENDING（media_id 主键，幂等），再机审占位直接放行到 APPROVED，
     * 以演示 PENDING -> APPROVED 审核闭环。</p>
     *
     * <p><b>幂等（适配 MQ at-least-once 重复投递 / 多消费者）</b>：进入即查当前态，
     * 已是终态则直接返回（APPROVED 兜底补齐时间线），不重复流转、不抛异常进 DLQ；
     * 仅 PENDING（含未落库）才走首次受理流程。同消息并发双消费由 RocketMQ「单消息单消费者」
     * 语义兜底，正常路径不会出现。</p>
     *
     * @param event 媒体上传事件（含 mediaId / userId / url / occurredAt）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        MediaStatus existing = mediaRepository.getStatus(event.mediaId(), userId);
        if (existing != null && existing != MediaStatus.PENDING) {
            // 重复投递：已是终态，幂等返回；APPROVED 兜底补齐公域时间线（ZADD 覆盖，天然幂等）
            if (existing == MediaStatus.APPROVED) {
                feedTimelineStore.append(new MediaItem(event.mediaId(), event.url(), existing, event.occurredAt()));
            }
            return;
        }
        // 首投（或仍在 PENDING）：落库受理态（主键幂等，重复 insert 不报错）
        mediaRepository.insert(event.mediaId(), userId, event.url(), MediaStatus.PENDING, event.occurredAt());

        // —— 机审（第一阶段，始终执行）——
        // 真实项目此处接入内容安全模型（鉴黄 / 暴恐 / 涉政 OCR 等），返回 APPROVED / REJECTED。
        // 当前 AutoPassModeration 为占位桩，恒返回 APPROVED（仅演示「机审通过」分支）；
        // 后续替换为真实模型即可，审核主流程无需改动。机审结果仅作辅助记录，不直接决定终态。
        MediaStatus machine = contentModeration.moderate(event.mediaId(), userId, event.url());

        if (properties.getReview().isAutoPass()) {
            // 演示占位：跳过真人审核，机审桩结果直接放行（仅供本地联调 / 克隆即跑）。
            // 生产务必关闭（auto-pass=false），否则 UGC 内容裸奔涉政涉黄。
            MediaStatus target = review(event.mediaId(), userId, machine == MediaStatus.APPROVED);
            if (target == MediaStatus.APPROVED) {
                feedTimelineStore.append(new MediaItem(event.mediaId(), event.url(), target, event.occurredAt()));
            }
            return;
        }

        // —— 真人审核（第二阶段，默认开启）——
        // 机审结果仅作辅助记录（当前桩恒 APPROVED）；内容一律停在 PENDING，
        // 由审核人员在审核页（review.html / admin.html → /api/admin/media/review?mediaId=...&approve=...）
        // 最终裁定 通过 / 驳回。通过即写入公域推荐流，驳回仅本人「我的内容」可见。
        // 此分支没有任何自动放行逻辑——即「真审核」闸，结构上不可能自动过。
        log.info("机审完成（机审结果={}），内容转入人工审核队列，等待审核人员裁定: mediaId={}, userId={}", machine, event.mediaId(), userId);
    }

    /**
     * 执行审核动作：PENDING -&gt; APPROVED / REJECTED（终态）。
     *
     * @param mediaId  内容唯一标识
     * @param userId   归属用户（分片键，保证按单分片精准更新）
     * @param approved 通过 / 驳回
     * @return 审核后的终态
     */
    public MediaStatus review(String mediaId, long userId, boolean approved) {
        MediaStatus current = mediaRepository.getStatus(mediaId, userId);
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未找到待审核记录: mediaId=" + mediaId);
        }
        if (current != MediaStatus.PENDING) {
            // 终态幂等：重复投递 / 并发流转已到达终态时直接返回当前态，不抛异常——
            // 避免重复消息被抛异常后触发 RocketMQ 重试 / 进 DLQ（handleUploaded 已保证终态安全）。
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
     * 管理员审核入口：按 mediaId 解析归属 userId（mediaId 内含 userId 分片键），
     * 驱动 PENDING → APPROVED / REJECTED。供 {@code /api/admin/media/review?mediaId=...} 调用，
     * 是「真审核」闸的执行点（机审 auto-pass 关闭时，仅此入口能把内容翻成终态）。
     *
     * @param mediaId  内容唯一标识（media/{userId}/{uuid}.{ext}）
     * @param approved true=通过 / false=驳回
     * @return 审核后的终态
     */
    public MediaStatus reviewByMediaId(String mediaId, boolean approved) {
        long userId = parseUserId(mediaId);
        MediaStatus target = review(mediaId, userId, approved);
        if (target == MediaStatus.APPROVED) {
            // 通过即写入公域时间线（与 auto-pass 分支口径一致）；
            // review() 只翻状态，不碰时间线，故此处补 append，保证内容进推荐流。
            MediaItem item = mediaRepository.findMedia(mediaId, userId);
            if (item != null) {
                feedTimelineStore.append(new MediaItem(item.mediaId(), item.url(), MediaStatus.APPROVED, item.createdAt()));
            }
        }
        return target;
    }

    /** 从 mediaId（media/{userId}/{uuid}.{ext}）解析归属 userId，用于审核接口分片路由。 */
    private static long parseUserId(String mediaId) {
        // mediaId 形如 media/{userId}/{uuid}.{ext}，第二段即分片键 userId
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
     * 失效单条状态缓存（旁路缓存 fail-open：异常不影响主流程）。
     *
     * <p>仅清状态缓存，不清公域推荐流——推荐流 TTL 仅 15s 自然会过期，最长 15s 延迟后
     * 公域可见性收敛，可接受的最终一致；主动批量清推荐流需 Redis keys/SCAN，存在阻塞风险，
     * 故不在此处理。</p>
     *
     * @param mediaId 内容唯一标识
     */
    private void evictCache(String mediaId) {
        try {
            redisTemplate.delete(STATUS_KEY_PREFIX + mediaId);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }
}
