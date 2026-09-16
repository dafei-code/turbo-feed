package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.event.MediaEventPublisher;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.idempotency.UploadIdempotency;
import com.turbofeed.gateway.service.presign.UploadReservation;
import com.turbofeed.gateway.service.presign.UploadReservationStore;
import com.turbofeed.gateway.service.processing.ImageProcessingChain;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.storage.MediaStorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 预签名直传的<b>异步收尾</b>：对象已由客户端直传到位、且通过服务端复检之后才轮到本类。
 *
 * <p><b>为什么单独成类而不是并进 {@link MediaUploadService}</b>：收尾跑在
 * {@code uploadFinalizeExecutor} 线程上，而 {@code @Async} 靠代理生效——
 * 必须由<b>另一个 Bean</b> 调用才走代理。若把 {@code @Async} 标在
 * {@code MediaUploadService} 自己的方法上，同类内部调用会绕过代理、退化为同步执行，
 * 上传线程反而被落库拖住（Spring 自调用失效的经典坑）。</p>
 *
 * <p><b>为什么不再读线程上下文</b>：异步线程没有 JWT 的 UserContext（ThreadLocal 不跨线程），
 * 故一切身份/文案信息都从 {@link UploadReservation} 取——那是在 presign 阶段由请求线程
 * 落下的、且已完成越权校验的快照。</p>
 *
 * <p><b>失败语义</b>：收尾失败即整帖作废——删除已传对象 + 物理删除可能已落的残行，
 * 与 {@code MediaUploadService#cleanupPartialPost} 同一口径（绝不留「缺代表行 / 张数不齐」
 * 的半成品帖）。清理动作本身失败只告警，不掩盖原始异常。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUploadFinalizer {

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final ImageProcessingChain processingChain;
    private final MediaJdbcRepository mediaRepository;
    private final MediaEventPublisher eventPublisher;
    private final UploadIdempotency idempotency;
    private final UploadReservationStore reservationStore;

    /**
     * 异步收尾：可选重编码 → 整帖批量落库（PENDING）→ 发一条帖级事件 → 回写幂等结果。
     *
     * <p>异常在本方法内收敛并记录，<b>不会</b>外泄到调用方（调用方早已返回 202 受理回执），
     * 因此这里是这类异步任务的最后一道 observable 边界，日志必须带 postId 便于排查。</p>
     */
    @Async("uploadFinalizeExecutor")
    public void finalizeAsync(UploadReservation reservation) {
        String postId = reservation.postId();
        List<String> mediaIds = reservation.slots().stream()
                .map(UploadReservation.Slot::mediaId)
                .toList();
        try {
            maybeReprocess(reservation);

            List<String> urls = mediaIds.stream()
                    .map(m -> properties.getPublicUrlBase() + m)
                    .toList();
            mediaRepository.batchInsert(buildRows(reservation, urls));

            // 整帖一条事件：与「整帖一审」配套，审核侧一次跑完，不做 N 张图的齐备性判断
            eventPublisher.publish(new MediaUploadedEvent(
                    postId, List.copyOf(mediaIds), List.copyOf(urls), reservation.userId(),
                    reservation.caption(), reservation.captionMark(),
                    reservation.requestId(), reservation.createdAt()));

            MediaItem post = new MediaItem(postId, mediaIds.get(0), urls.get(0), List.copyOf(urls), 0,
                    MediaStatus.PENDING, reservation.createdAt(),
                    reservation.caption(), reservation.captionMark());
            idempotency.store(reservation.userId(), reservation.requestId(), post);

            log.info("预签名上传收尾完成: postId={}, images={}, userId={}",
                    postId, mediaIds.size(), reservation.userId());
        } catch (RuntimeException e) {
            log.error("预签名上传收尾失败，开始补偿清理: postId={}, userId={}",
                    postId, reservation.userId(), e);
            cleanup(reservation.userId(), postId, mediaIds);
        } finally {
            reservationStore.delete(postId);
        }
    }

    /**
     * 处理链（可选）：下载 → 重编码 → 覆盖写回同一对象名。
     *
     * <p><b>与旧链路的差异</b>：网关收字节流时，处理发生在存储<b>之前</b>；
     * 客户端直传后字节已落对象存储，处理只能发生在存储<b>之后</b>（先下载再回写）。
     * 这正是抖音「先直传、再异步转码」的形状，代价是处理链开启时会多一次网关↔对象存储的往返
     * ——所以默认仍关闭（{@code processing-enabled=false}），保持零额外 IO。</p>
     */
    private void maybeReprocess(UploadReservation reservation) {
        if (!properties.isProcessingEnabled()) {
            return;
        }
        for (UploadReservation.Slot slot : reservation.slots()) {
            // 与上传主链路同一取舍：webp 无 ImageIO 编解码、gif 保留动图，跳过处理
            if (slot.format() == ImageFormat.WEBP || slot.format() == ImageFormat.GIF) {
                continue;
            }
            try {
                byte[] raw = storageClient.download(slot.mediaId());
                byte[] processed = processingChain.process(raw, slot.format().extension());
                if (processed != null) {
                    // 覆盖写回同名对象：mediaId 与 URL 均不变，已落库的引用无需更新
                    storageClient.overwrite(slot.mediaId(), processed, slot.format().contentType());
                }
            } catch (Exception e) {
                // 处理失败降级保留原图：绝不因重编码失败丢掉用户已传成功的内容
                log.warn("直传后处理失败，降级保留原图: mediaId={}, {}", slot.mediaId(), e.getMessage());
            }
        }
    }

    /** 组装整帖待落库行（同 user_id 落同片，batchInsert 合并为单批）。 */
    private List<MediaJdbcRepository.MediaRowSpec> buildRows(UploadReservation r, List<String> urls) {
        long uid = Long.parseLong(r.userId());
        List<MediaJdbcRepository.MediaRowSpec> rows = new ArrayList<>(r.slots().size());
        for (int i = 0; i < r.slots().size(); i++) {
            UploadReservation.Slot slot = r.slots().get(i);
            rows.add(new MediaJdbcRepository.MediaRowSpec(
                    r.postId(), slot.mediaId(), uid, urls.get(i), MediaStatus.PENDING,
                    r.caption(), r.captionMark(), slot.seq(), r.createdAt()));
        }
        return rows;
    }

    /** 收尾失败的补偿清理：删对象（fail-open）+ 物理删除本批已落库的残行。 */
    private void cleanup(String userId, String postId, List<String> mediaIds) {
        for (String mediaId : mediaIds) {
            try {
                storageClient.delete(mediaId);
            } catch (Exception e) {
                log.warn("补偿清理：删除对象失败（可能残留对象）: mediaId={}, {}", mediaId, e.getMessage());
            }
        }
        try {
            mediaRepository.hardDeleteByPost(Long.parseLong(userId), postId);
        } catch (Exception e) {
            log.error("补偿清理：物理删除残行失败，可能残留半成品帖: postId={}, userId={}", postId, userId, e);
        }
    }
}
