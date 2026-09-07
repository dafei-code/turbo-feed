package com.turbofeed.gateway.service.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.feed.FeedTimelineStore;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 媒体查询服务（读侧）：提供「用户 → 其上传内容」的列表与单条状态查询。
 *
 * <p><b>职责演变</b>：此前维护进程内「用户维度倒排索引」({@code userIndex})，重启即丢失，
 * 现已由 {@link MediaJdbcRepository} 对 media 表（按 user_id 分片）的查询取代，
 * 单一事实源、与审核状态同表，无需双写、无内存态。</p>
 *
 * <p><b>身份隔离</b>：userId 来自 JWT（{@code UserContextHolder.requireUserId()}），
 * 不经方法入参、客户端无法传入他人 ID 越权查看。列表/状态查询均带 user_id 分片键，
 * 精准命中单分片，不广播。</p>
 *
 * <p><b>P2 读缓存</b>：公域推荐流与单条状态查询接入 Redis 旁路缓存（fail-open 降级）。
 * 缓存未命中/异常均回源 DB，绝不因缓存故障阻断主流程；命中则显著降低 DB 读压力
 * （对标抖音级读多写少场景）。缓存值与 DB 单一事实源一致；写入侧（审核通过）精确失效
 * 单条状态缓存；推荐流 TTL 短（15s），以最终一致换简单与无阻塞（不主动批量清推荐流）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQueryService {

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 公域推荐流缓存 TTL：15s（短 TTL 换最终一致，避免批量失效 Redis 阻塞） */
    private static final Duration REC_CACHE_TTL = Duration.ofSeconds(15);
    /** 单条状态缓存 TTL：60s（个人中心轮询刷新，较长 TTL 进一步降 DB 压力） */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(60);
    private static final String REC_KEY_PREFIX = "tf:feed:rec:";
    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /**
     * 查询指定用户上传的内容列表（按上传时间倒序，分页）。
     *
     * @param userId        归属用户（来自 JWT，非客户端可控）
     * @param statusFilter  状态过滤（null = 不过滤，供个人中心全量/统计使用）
     * @param page          页码（从 0 开始）
     * @param size          单页条数（≤0 兜底为 50）
     * @return 该用户的内容列表（当前页），从未上传过则返回空列表
     */
    public List<MediaItem> listByUser(String userId, MediaStatus statusFilter, int page, int size) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(page, 0) * limit;
        return mediaRepository.listByUser(Long.parseLong(userId), statusFilter, limit, offset);
    }

    /**
     * 公域推荐流（占位实现）：透传 {@link MediaJdbcRepository#listApprovedGlobal} 的分页查询，
     * 叠加 Redis 旁路缓存（key 含 page/size，TTL 15s）。
     * 仅用于演示/小数据量，生产须由推荐服务 + 异构索引取代（见 changelog 0017 / 0018）。
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数（≤0 兜底为 20）
     */
    public List<MediaItem> listRecommended(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(page, 0) * limit;
        String key = REC_KEY_PREFIX + page + ":" + limit;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MediaItem.class));
            }
            // 优先读写时物化的时间线（O(log n)/页，零扫分片库）；空结果（含 Redis 未就绪）降级回源
            List<MediaItem> fresh = feedTimelineStore.readPage(page, limit);
            if (fresh.isEmpty()) {
                log.warn("时间线为空，降级回源分片库扫描: page={}, size={}", page, size);
                fresh = mediaRepository.listApprovedGlobal(limit, offset);
            }
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fresh), REC_CACHE_TTL);
            return fresh;
        } catch (Exception e) {
            // 旁路缓存 fail-open：任何缓存异常均回源 DB，不阻断读路径
            log.warn("推荐流缓存读取失败，降级回源 DB: key={}, {}", key, e.getMessage());
            return mediaRepository.listApprovedGlobal(limit, offset);
        }
    }

    /**
     * 查询单条内容的当前审核状态，叠加 Redis 旁路缓存（TTL 60s）。
     *
     * <p>查不到时返回 {@link MediaStatus#PENDING} 而非 null：内容已受理但审核事件
     * 尚未到达（异步模式下存在该窗口），对前端而言就是「处理中」，语义正确且避免
     * 前端处理 null 分支。</p>
     *
     * @param mediaId 内容唯一标识
     * @param userId  归属用户（分片键，保证按单分片精准查询）
     * @return 当前状态，未查到时按 PENDING 处理
     */
    public MediaStatus statusOf(String mediaId, String userId) {
        String key = STATUS_KEY_PREFIX + mediaId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return MediaStatus.valueOf(cached);
            }
            MediaStatus resolved = resolve(mediaId, userId);
            redisTemplate.opsForValue().set(key, resolved.name(), STATUS_CACHE_TTL);
            return resolved;
        } catch (Exception e) {
            // 旁路缓存 fail-open：回源 DB
            log.warn("状态缓存读取失败，降级回源 DB: mediaId={}, {}", mediaId, e.getMessage());
            return resolve(mediaId, userId);
        }
    }

    private MediaStatus resolve(String mediaId, String userId) {
        MediaStatus status = mediaRepository.getStatus(mediaId, Long.parseLong(userId));
        return status != null ? status : MediaStatus.PENDING;
    }
}
