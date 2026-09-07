package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.feed.FeedTimelineStore;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /**
     * 上传事件处理入口（Observer 两种事件源共用）：
     * {@link com.turbofeed.gateway.service.event.ReviewListener}（本地 Spring 事件）与
     * {@code MediaReviewConsumer}（RocketMQ）均调用本方法，保证审核逻辑单一来源。
     *
     * <p>流程：先落库受理态 PENDING（media_id 主键，幂等），再机审占位直接放行到 APPROVED，
     * 以演示 PENDING -> APPROVED 审核闭环。</p>
     *
     * @param event 媒体上传事件（含 mediaId / userId / url / occurredAt）
     */
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        mediaRepository.insert(event.mediaId(), userId, event.url(), MediaStatus.PENDING, event.occurredAt());
        review(event.mediaId(), userId, true);
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
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "终态不可再次审核: mediaId=" + mediaId + ", status=" + current);
        }
        MediaStatus target = approved ? MediaStatus.APPROVED : MediaStatus.REJECTED;
        mediaRepository.updateStatus(mediaId, userId, target);
        evictCache(mediaId);
        log.info("审核状态流转: mediaId={}, {} -> {}", mediaId, current, target);
        return target;
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
