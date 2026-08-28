package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体审核服务：维护内容审核状态，执行唯一的合法转换 PENDING -&gt; APPROVED / REJECTED。
 *
 * <p><b>存储边界（重要）</b>：当前状态存于内存 {@link ConcurrentHashMap}——按本轮约束
 * 不引入 MySQL，故为<b>进程内临时态，重启即丢失</b>，仅用于演示审核闭环。
 * TODO(MySQL): media 元数据表（id, user_id, url, status, created_at）落地后，
 * 由 DB 持久化当前状态，内存注册表退役。</p>
 *
 * <p>状态约束：仅 PENDING 可转终态（APPROVED / REJECTED），终态不可再流转——这一条
 * 合法性检查直接落在 {@link #review} 内（单路径转换无需独立状态机）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaReviewService {

    /** 审核状态注册表（内存态，重启丢失；元数据表落地后替换为 DB 持久化）。 */
    private final Map<String, MediaStatus> statusRegistry = new ConcurrentHashMap<>();

    /**
     * 受理审核：内容上传成功即进入待审核态（PENDING）。
     *
     * @param mediaId 内容唯一标识（media/{userId}/{uuid}.{ext}）
     * @return 受理后的状态（PENDING）
     */
    public MediaStatus submitForReview(String mediaId) {
        MediaStatus prev = statusRegistry.putIfAbsent(mediaId, MediaStatus.PENDING);
        if (prev != null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "重复提交审核: mediaId=" + mediaId);
        }
        log.info("审核状态流转: mediaId={}, -> PENDING", mediaId);
        return MediaStatus.PENDING;
    }

    /**
     * 执行审核动作：PENDING -&gt; APPROVED / REJECTED（终态）。
     *
     * @param mediaId  内容唯一标识
     * @param approved 通过 / 驳回
     * @return 审核后的终态
     */
    public MediaStatus review(String mediaId, boolean approved) {
        MediaStatus current = statusRegistry.get(mediaId);
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未找到待审核记录: mediaId=" + mediaId);
        }
        if (current != MediaStatus.PENDING) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "终态不可再次审核: mediaId=" + mediaId + ", status=" + current);
        }
        MediaStatus target = approved ? MediaStatus.APPROVED : MediaStatus.REJECTED;
        statusRegistry.put(mediaId, target);
        log.info("审核状态流转: mediaId={}, {} -> {}", mediaId, current, target);
        return target;
    }

    /**
     * 上传事件处理入口（Observer 两种事件源共用）：
     * {@link ReviewListener}（本地 Spring 事件）与 {@code MediaReviewConsumer}（RocketMQ）
     * 均调用本方法，保证审核逻辑单一来源。
     *
     * @param event 媒体上传事件
     */
    public void handleUploaded(MediaUploadedEvent event) {
        submitForReview(event.mediaId());
        // TODO(内容安全): 机审占位——真实实现调用内容安全 API（如腾讯云天御 / 阿里绿网）
        //  后按机器审核结果决定 APPROVED / REJECTED，必要时转人工复核。
        //  当前直接放行，以演示 PENDING -> APPROVED 审核闭环。
        review(event.mediaId(), true);
    }

    /** 查询当前审核状态（前端展示过滤 / 客户端进度查询用）。 */
    public MediaStatus getStatus(String mediaId) {
        return statusRegistry.get(mediaId);
    }
}
