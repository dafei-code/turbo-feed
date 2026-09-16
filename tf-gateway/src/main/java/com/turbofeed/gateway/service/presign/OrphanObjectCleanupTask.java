package com.turbofeed.gateway.service.presign;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.storage.MediaStorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 孤儿对象清理：回收「客户端已直传、但始终没有落库」的对象存储对象。
 *
 * <p><b>为什么必须有它</b>：预签名直传把「字节落存储」与「元数据落库」拆成了两步。
 * 客户端传完 bytes 后如果不再调用 {@code complete}（放弃上传 / 断网 / 上传中途退出），
 * 或者网关在收尾前崩溃，对象存储里就会留下一个<b>没有任何 DB 行引用</b>的对象——
 * 它不出现在任何列表里、用户也删不掉，只能由服务端主动回收。这是直传架构的固有成本，
 * 不是缺陷：抖音同款链路同样需要一个「未提交对象」的回收机制。</p>
 *
 * <p><b>判定口径（保守优先）</b>：只清理 {@code UploadReservationStore} 中<b>已过预约有效期</b>
 * 且仍未被释放的条目。正常完成或已补偿清理都会主动释放，因此「逾期仍在」= 确定没有对应帖子。
 * 宁可晚一点删，也绝不删一个可能还在用的对象。</p>
 *
 * <p><b>失败处理</b>：删除失败会把该对象重新入队（延后一分钟再试），避免一次抖动就永久漏删；
 * 反复失败会持续出现在 WARN 日志里，属于需要人工介入的信号。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanObjectCleanupTask {

    /** 删除失败后的重试延后时间（ms）：避免瞬时故障导致的对象永久漏删，也不至于死循环重试。 */
    private static final long RETRY_DELAY_MILLIS = 60_000L;

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final UploadReservationStore reservationStore;

    /**
     * 扫描并清理过期孤儿。周期由 {@code turbofeed.media.presign.orphan-cleanup-fixed-delay-ms}
     * 控制（默认 10 分钟）；{@code fixedDelay} 而非 {@code fixedRate}——上一轮没跑完不会并发叠加。
     */
    @Scheduled(fixedDelayString = "${turbofeed.media.presign.orphan-cleanup-fixed-delay-ms:600000}")
    public void sweep() {
        if (!properties.getPresign().isOrphanCleanupEnabled()) {
            return;
        }
        List<String> orphans = reservationStore.takeExpiredPending(
                properties.getPresign().getOrphanCleanupBatch());
        if (orphans.isEmpty()) {
            return;
        }
        int deleted = 0;
        for (String mediaId : orphans) {
            try {
                storageClient.delete(mediaId);
                deleted++;
            } catch (Exception e) {
                log.warn("孤儿对象删除失败，已重新入队待下轮重试: mediaId={}, {}", mediaId, e.getMessage());
                reservationStore.markPending(List.of(mediaId), System.currentTimeMillis() + RETRY_DELAY_MILLIS);
            }
        }
        log.info("孤儿对象清理: 扫描={}, 删除成功={}, 失败={}", orphans.size(), deleted, orphans.size() - deleted);
    }
}
