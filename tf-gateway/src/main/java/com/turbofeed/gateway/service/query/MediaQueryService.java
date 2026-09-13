package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.config.FeedEngineProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
 * <p><b>Feed 读写分离（B1 服务拆分后）</b>：公域推荐流的数据源已迁至 tf-feed-engine，
 * 本服务不再持有任何 Feed 读模型或推荐流缓存——推荐流经 {@link FeedEngineClient} 远程读取，
 * 单条状态查询仍走本地 Redis 旁路缓存（TTL 60s）。两条路径的职责边界因此变得清晰：
 * 「内容状态」属网关（审核状态机在同一进程），「内容可见性」属引擎。</p>
 *
 * <p><b>降级口径</b>：引擎不可用时按 {@link FeedEngineProperties.DegradedMode} 处理，
 * 默认 {@code EMPTY}（返回空列表，绝不跨分片广播）；详见该枚举的取舍说明。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQueryService {

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final FeedEngineClient feedEngineClient;
    private final FeedEngineProperties feedProperties;

    /** 单条状态缓存 TTL：60s（个人中心轮询刷新，较长 TTL 进一步降 DB 压力） */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(60);
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
     * 公域推荐流：转发到 tf-feed-engine 的时间线读模型（O(log n)/页，零扫分片库）。
     *
     * <p>本方法只做三件事：调用引擎、判定降级、返回网关视图。推荐流的旁路缓存（TTL 15s）
     * 与时间线 ZSET 同域放在引擎侧，网关不再压第二层缓存。</p>
     *
     * <p><b>注意区分两种"空"</b>：引擎正常但发现流确实没有内容（返回空列表）与引擎不可用
     * 是两回事——前者是正常业务结果，后者才触发降级。这样引擎短暂抖动不会把
     * "假空"写进任何缓存层。</p>
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数（≤0 兜底 20）
     */
    public List<MediaItem> listRecommended(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        Optional<List<MediaItem>> fromEngine = feedEngineClient.recommended(page, limit);
        if (fromEngine.isPresent()) {
            return fromEngine.get();
        }
        if (feedProperties.getDegradedMode() == FeedEngineProperties.DegradedMode.LOCAL_SCAN) {
            // 演示口径：等价于拆分前的行为——带回源跨分片广播，生产禁用
            long offset = (long) Math.max(page, 0) * limit;
            log.warn("Feed 引擎不可用，降级回源分片库广播查询（degraded-mode=local-scan，仅限本地/演示）: page={}, size={}",
                    page, limit);
            return mediaRepository.listApprovedGlobal(limit, offset);
        }
        log.warn("Feed 引擎不可用，降级返回空列表（degraded-mode=empty，拒绝跨分片广播）: page={}, size={}",
                page, limit);
        return List.of();
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
