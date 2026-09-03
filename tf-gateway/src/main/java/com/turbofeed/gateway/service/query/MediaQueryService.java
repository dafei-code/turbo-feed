package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.review.MediaReviewService;
import com.turbofeed.gateway.service.review.MediaStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 媒体查询服务（读侧）：维护"用户 → 其上传内容"的索引，供列表与状态查询使用。
 *
 * <p><b>为什么需要独立索引</b>：{@code MediaReviewService} 的注册表只有
 * {@code mediaId → status} 单向映射，无法回答"某个用户传过哪些内容"。
 * 补一个用户维度的倒排索引，列表接口才成立。</p>
 *
 * <p><b>写入时机</b>：由事件驱动——{@code MediaIndexListener}（本地事件）与
 * {@code MediaReviewConsumer}（RocketMQ）在各自通道内调用 {@link #index}，
 * 与审核流转并列，二者互不阻塞。索引只记内容事实（标识 / 地址 / 时间），
 * 不冗余审核状态——状态每次查询时实时取，避免双写不一致。</p>
 *
 * <p><b>存储边界</b>：与审核状态同属进程内内存态，<b>重启即丢失</b>。
 * 这是与"不引入 MySQL"约束一致的临时实现；media 元数据表落地后，本索引
 * 由 {@code SELECT ... WHERE user_id = ?} 取代（见 {@code MediaReviewService} 的 TODO）。</p>
 *
 * <p><b>并发</b>：外层 {@link ConcurrentHashMap} + 内层 {@link CopyOnWriteArrayList}，
 * 契合"读多写少"（列表查询远多于上传）。COW 在写入时复制数组，
 * 故列表遍历拿到的是不可变快照，无需额外加锁，也不会抛
 * {@code ConcurrentModificationException}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQueryService {

    private final MediaReviewService reviewService;

    /** 用户维度倒排索引：userId → 该用户上传的内容（按写入顺序）。 */
    private final Map<String, CopyOnWriteArrayList<IndexedMedia>> userIndex = new ConcurrentHashMap<>();

    /**
     * 建立内容索引（事件驱动，幂等：同一 mediaId 重复到达只保留一条）。
     *
     * @param event 媒体上传事件（本地事件与 RocketMQ 消息共用同一载体）
     */
    public void index(MediaUploadedEvent event) {
        CopyOnWriteArrayList<IndexedMedia> owned = userIndex.computeIfAbsent(
                event.userId(), key -> new CopyOnWriteArrayList<>());
        // 幂等：MQ 重投时事件会重复到达，重复追加会让列表出现重复项
        owned.addIfAbsent(new IndexedMedia(event.mediaId(), event.url(), event.occurredAt()));
        log.debug("内容索引建立: userId={}, mediaId={}", event.userId(), event.mediaId());
    }

    /**
     * 查询指定用户上传的内容列表（按上传时间倒序，最新在前）。
     *
     * <p>身份不入参也能保证隔离：调用方从 {@code UserContextHolder.requireUserId()}
     * 取 userId，客户端无法传入他人 ID 越权查看。</p>
     *
     * @param userId 归属用户（来自 JWT，非客户端可控）
     * @return 该用户的内容列表，从未上传过则返回空列表
     */
    public List<MediaItem> listByUser(String userId) {
        CopyOnWriteArrayList<IndexedMedia> owned = userIndex.get(userId);
        if (owned == null || owned.isEmpty()) {
            return List.of();
        }
        return owned.stream()
                .map(media -> new MediaItem(media.mediaId(), media.url(),
                        statusOf(media.mediaId()), media.createdAt()))
                .sorted(Comparator.comparing(MediaItem::createdAt).reversed())
                .toList();
    }

    /**
     * 查询单条内容的当前审核状态。
     *
     * <p>查不到时返回 {@link MediaStatus#PENDING} 而非 null：内容已索引但审核事件
     * 尚未到达（异步模式下存在该窗口），对前端而言就是"处理中"，语义正确且避免
     * 前端处理 null 分支。</p>
     *
     * @param mediaId 内容唯一标识
     * @return 当前状态，未查到时按 PENDING 处理
     */
    public MediaStatus statusOf(String mediaId) {
        MediaStatus status = reviewService.getStatus(mediaId);
        return status != null ? status : MediaStatus.PENDING;
    }

    /** 索引项：只记内容本身的事实，不含审核状态（状态实时取自审核服务）。 */
    private record IndexedMedia(String mediaId, String url, Instant createdAt) {
    }
}
