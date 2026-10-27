package com.turbofeed.gateway.service.query;

import com.turbofeed.shared.redis.RedisKeys;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.client.FeedRecommendedPage;
import com.turbofeed.gateway.config.FeedEngineProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.feed.InteractionEventService;
import com.turbofeed.gateway.service.like.MediaLikeService;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final InteractionEventService interactionEventService;
    private final MediaLikeService mediaLikeService;

    /** keyset 游标分页开关（P3-PR07）：默认开（true）走 keyset 游标分页；关则回退页码分页，兼容老调用方与回滚。 */
    @Value("${turbofeed.feed.cursor-paging-enabled:true}")
    private boolean cursorPagingEnabled;

    /** 引擎共享实时分前缀（{@code tf:post:stat:{timelineKey}} Hash；与引擎 PostStatService 同命名空间，复用现有 Redis）。 */
    private static final String POST_STAT_PREFIX = RedisKeys.POST_STAT;

    /** 单条状态缓存 TTL：60s（个人中心轮询刷新，较长 TTL 进一步降 DB 压力） */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(60);
    private static final String STATUS_KEY_PREFIX = RedisKeys.MEDIA_STATUS;

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
     * <p>本方法只做四件事：调用引擎、判定降级、注入抖音式计数、返回分页视图。推荐流的旁路缓存
     * （TTL 15s）与时间线 ZSET 同域放在引擎侧，网关不再压第二层缓存。</p>
     *
     * <p><b>keyset 游标分页（P3-PR07）</b>：{@code cursor} 非空且开关
     * {@code turbofeed.feed.cursor-paging-enabled=true}（默认）时走 keyset 游标分页——向引擎传
     * {@code cursor}{@code size}、不再传 {@code page}，游标由引擎基于入流 score 计算并以不透明
     * token 返回（前端只读 {@code nextCursor}）。{@code cursor} 为空或开关关闭时回退页码分页
     * （传 {@code page}{@code size}），行为与改造前完全一致，保证老调用方兼容。</p>
     *
     * <p><b>注意区分两种"空"</b>：引擎正常但发现流确实没有内容（返回空页）与引擎不可用
     * 是两回事——前者是正常业务结果，后者才触发降级。这样引擎短暂抖动不会把"假空"写进任何缓存层。</p>
     *
     * @param page   页码（从 0 开始；cursor 模式由调用方传 0）
     * @param size   单页条数（≤0 兜底 20）
     * @param userId 个性化用户（已登录态；匿名为 {@code null}），透传给引擎做兴趣召回/排序
     * @param cursor 续拉游标（空 = 首页，走页码分页）
     * @return 分页视图 {@link FeedRecommendedPage}（items / nextCursor / hasMore）
     */
    public FeedRecommendedPage listRecommended(int page, int size, String userId, String cursor) {
        int limit = size <= 0 ? 20 : size;
        boolean useCursor = cursorPagingEnabled && cursor != null && !cursor.isBlank();
        // 游标模式把 cursor 透传给引擎、且不传 page；页码模式传 page、cursor 置空（引擎按 cursor==null 走页码分支）。
        Optional<FeedRecommendedPage> fromEngine = feedEngineClient.recommended(page, limit, userId, useCursor ? cursor : null);
        if (fromEngine.isPresent()) {
            FeedRecommendedPage pageView = fromEngine.get();
            // 抖音式卡片计数注入：点赞数（网关 Redis Set 基数，去重权威值）/ 已赞态 /
            // 评论数（引擎共享实时分 comments 字段，客户端 COMMENT 行为事件实时累加）。
            List<MediaItem> enriched = enrichCounts(pageView.items(), userId);
            emitImpressions(userId, enriched, page);
            return new FeedRecommendedPage(enriched, pageView.nextCursor(), pageView.hasMore());
        }
        // 引擎不可用 → 降级（保全接口形态，绝不跨分片广播）
        return degrade(page, limit, userId);
    }

    /**
     * 引擎不可用时降级（接口形态不变，仍返回 {@link FeedRecommendedPage}）。
     *
     * <p>默认 {@code EMPTY} 返回空页（拒绝跨分片广播）；{@code LOCAL_SCAN} 为演示口径，
     * 等价于拆分前的行为——带回源跨分片广播，生产禁用。</p>
     */
    private FeedRecommendedPage degrade(int page, int limit, String userId) {
        if (feedProperties.getDegradedMode() == FeedEngineProperties.DegradedMode.LOCAL_SCAN) {
            long offset = (long) Math.max(page, 0) * limit;
            log.warn("Feed 引擎不可用，降级回源分片库广播查询（degraded-mode=local-scan，仅限本地/演示）: page={}, size={}",
                    page, limit);
            List<MediaItem> items = enrichCounts(mediaRepository.listApprovedGlobal(limit, offset), userId);
            return new FeedRecommendedPage(items, "", false);
        }
        log.warn("Feed 引擎不可用，降级返回空列表（degraded-mode=empty，拒绝跨分片广播）: page={}, size={}",
                page, limit);
        return new FeedRecommendedPage(List.of(), "", false);
    }

    /**
     * 服务端曝光发射（A1 训练样本底座）：推荐流组页返回时，对每条内容异步记一次 IMPRESSION。
     *
     * <p>匿名用户（userId 为空）跳过——个性化训练样本需绑定用户；异步 fire-and-forget，
     * 不阻塞返回（落库失败由 {@link InteractionEventService} fail-open 兜底）。</p>
     *
     * @param userId 已登录用户（来自 JWT）；匿名为 null
     * @param items  本次返回的推荐内容
     * @param page   页码
     */
    private void emitImpressions(String userId, List<MediaItem> items, int page) {
        if (userId == null || userId.isBlank() || items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            String key = item.timelineKey();
            if (key != null && !key.isBlank()) {
                interactionEventService.recordImpression(userId, key, page, i);
            }
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

    /**
     * 给推荐流卡片注入点赞/评论计数与已赞态（抖音式「计数接卡片」）。
     *
     * <p><b>A1 批量化</b>：改造前在循环内逐条调用 {@code likeCount}（64 次 SCARD）+
     * {@code isLiked}（1 次 SISMEMBER）+ {@code commentCountOf}（1 次 HGET），一页 20 条 =
     * <b>1320 个 Redis 命令、60 次独立往返</b>。现在三类计数各用一次批量读取：
     * 点赞数走 {@code MGET}、已赞态走一次 pipeline {@code SISMEMBER}、评论数走一次 pipeline
     * {@code HGET}——热路径降到 <b>约 60 个命令、3 次往返</b>，且与页大小无关地保持常数次往返。</p>
     *
     * <p>逐条以时间线幂等键 {@link MediaItem#timelineKey()} 为身份：点赞数取网关计数 Key
     * {@code tf:like:count:{postId}}（与 64 槽去重集合同步维护），已赞态取 {@code SISMEMBER}
     * （匿名用户按未赞），评论数取引擎共享实时分 {@code tf:post:stat:{postId}.comments}
     * （与引擎 PostStatService 同命名空间，复用现有 Redis，避免再查分片库）。</p>
     *
     * <p>fail-open：批量读取整体失败时对应计数按 0 / 未赞处理，绝不因计数缺失让整页失败。</p>
     */
    private List<MediaItem> enrichCounts(List<MediaItem> items, String userId) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        boolean authed = userId != null && !userId.isBlank();
        List<String> keys = new ArrayList<>(items.size());
        for (MediaItem it : items) {
            keys.add(it.timelineKey());
        }
        // 三类计数各一次批量读取（往返次数与页大小无关）
        Map<String, Long> likeCounts = mediaLikeService.likeCounts(keys);
        Map<String, Boolean> likedSet = authed
                ? mediaLikeService.isLikedBatch(keys, Long.parseLong(userId))
                : Map.of();
        Map<String, Long> commentCounts = commentCountsOf(keys);

        List<MediaItem> out = new ArrayList<>(items.size());
        for (MediaItem it : items) {
            String tk = it.timelineKey();
            out.add(it.withCounts(
                    likeCounts.getOrDefault(tk, 0L),
                    commentCounts.getOrDefault(tk, 0L),
                    Boolean.TRUE.equals(likedSet.get(tk))));
        }
        return out;
    }

    /**
     * 批量评论数：引擎共享实时分 {@code comments} 字段（客户端 COMMENT 行为事件实时累加）。
     * 一次 pipeline {@code HGET} 取 N 条；读不到的项不出现在结果里（调用方按 0 处理）。
     */
    private Map<String, Long> commentCountsOf(List<String> timelineKeys) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (timelineKeys.isEmpty()) {
            return result;
        }
        try {
            List<Object> vals = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String tk : timelineKeys) {
                    connection.hashCommands().hGet(
                            (POST_STAT_PREFIX + tk).getBytes(StandardCharsets.UTF_8),
                            "comments".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            for (int i = 0; i < timelineKeys.size(); i++) {
                result.put(timelineKeys.get(i), toLong(vals.get(i)));
            }
        } catch (Exception e) {
            log.warn("批量评论数读取失败（按 0 处理）: size={}, {}", timelineKeys.size(), e.getMessage());
        }
        return result;
    }

    /** pipeline 返回值归一化为 long（缺失 / 非数字按 0）。 */
    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
