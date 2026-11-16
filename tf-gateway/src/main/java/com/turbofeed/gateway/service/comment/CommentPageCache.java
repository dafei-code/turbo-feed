package com.turbofeed.gateway.service.comment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 评论列表页短 TTL 缓存（评审优化项④，<b>默认关闭</b>）。
 *
 * <h3>为什么需要它</h3>
 * <p>评论列表直接查 DB：{@code comment} 表按 media_id 分 128 片，一个爆款帖的所有翻页查询
 * 都落在同一张物理表。有 {@code idx_media_root_created} 前缀保护不会深扫，但单分片读 QPS
 * 有上限，突发流量下翻页读会把这一个分片打满。首页虽然已有 ZSET 热头，热头只给了 ID，
 * 正文仍要靠 {@code WHERE comment_id IN(...)} 回表；深页更是纯 keyset 查 DB。</p>
 *
 * <h3>缓存什么、不缓存什么</h3>
 * <ul>
 *   <li><b>只缓存 DB 行的列表快照</b>（{@code List<Comment>}，即「算 items」那一段）；
 *       命中后外层仍会执行 {@link CommentService#enrichLikeCounts}（Redis 实时点赞数回填）
 *       与 {@link CommentCountService#getCount}（Redis 评论总数），所以点赞数/总数不会被 TTL 冻住。</li>
 *   <li><b>不缓存整页 {@link CommentPage}</b>：nextCursor / hasMore 由 items 现算，避免游标与
 *       快照内容在 TTL 内错位。</li>
 *   <li>返回前统一 {@code new ArrayList<>(...)}：反序列化出的列表与 DB 返回的列表都必须可变，
 *       因为 enrichLikeCounts 会 {@code items.set(i, ...)} 覆盖 likeCount。</li>
 * </ul>
 *
 * <h3>新鲜度口径（重要）</h3>
 * <p>缓存是纯短 TTL（默认 2s，范围 200ms~30s）+ <b>落库成功后主动失效</b>。失效走
 * generation Key 的 {@code INCR}，而不是 {@code KEYS/SCAN} 删页：缓存 Key 带 generation，
 * 一代失效后旧页自然不可达、由 TTL 自净，O(1) 且不会在 Redis 上做大范围扫描。</p>
 *
 * <p><b>为什么失效挂在落库成功之后、而不是 publish 主链路里</b>：评论落库是异步的
 * （{@link LocalCommentEventPublisher} / {@link CommentPersistConsumer}）。若在 publish 里立刻
 * 失效，第一次读会发生在 DB 尚无新行时，把「缺新评论」的旧列表重新回填进缓存，反而把
 * 不一致又冻住一个 TTL。落库成功后失效，则「新评论可见延迟」与不加缓存时完全一致
 * （都取决于异步落库完成时间），缓存不会让语义变差。</p>
 *
 * <p>代价：爆款帖发布 QPS 很高时 generation 频繁推进，命中率会下降——这是读缓存与
 * 高频写的固有矛盾，开关默认关闭，只在「读多写少且单分片读已成瓶颈」的帖子上启用。
 * 详见 docs/ops/comment-read-scaling.md。</p>
 *
 * <h3>fail-open</h3>
 * <p>开关关闭时不产生任何 Redis 读写；开启后 Redis/Jackson 任一异常都只 WARN + 计数，
 * 回源 DB，绝不把异常抛给读请求。缓存 Key 用 SHA-256 摘要而不拼原始入参：mediaId 形如
 * {@code media/{uid}/{uuid}.{ext}} 含斜杠且用户可控，避免超长 Key 与原样入参进入 Redis。</p>
 */
@Component
public class CommentPageCache {

    private static final Logger log = LoggerFactory.getLogger(CommentPageCache.class);

    public static final String KEY_PREFIX = "tf:comment:page:";
    public static final String GEN_PREFIX = "tf:comment:page:gen:";

    private static final long MIN_TTL_MS = 200L;
    private static final long MAX_TTL_MS = 30_000L;
    private static final TypeReference<List<Comment>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter errorCounter;
    private final Counter evictCounter;

    public CommentPageCache(StringRedisTemplate redis,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry,
                            @Value("${" + FeatureFlagKeys.COMMENT_PAGE_CACHE_ENABLED + ":false}") boolean enabled,
                            @Value("${turbofeed.comment.page-cache-ttl-ms:2000}") long ttlMs) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        long bounded = Math.max(MIN_TTL_MS, Math.min(ttlMs, MAX_TTL_MS));
        this.ttl = Duration.ofMillis(bounded);
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.hitCounter = counter(registry, "hit");
        this.missCounter = counter(registry, "miss");
        this.errorCounter = counter(registry, "error");
        this.evictCounter = Counter.builder("tf_comment_page_cache_evict_total")
                .description("评论页缓存 generation 失效次数")
                .register(registry);
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("tf_comment_page_cache_total")
                .description("评论列表页缓存读取结果计数")
                .tag("result", result)
                .register(registry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * 读一页评论：命中直接返回 DB 行快照，未命中回源并把结果写回缓存。
     *
     * @param mediaId 内容 ID（同时决定 generation 归属）
     * @param rootId  0 = 顶层列表，&gt;0 = 某根评论下的回复
     * @param size    本页大小（已由调用方 clamp）
     * @param cursor  keyset 游标（空串 = 首页）
     * @param mode    {@code newest} / {@code hot}（顶层列表排序）
     * @param loader  回源 DB 的加载器（未启用缓存时直接执行）
     */
    public List<Comment> load(String mediaId, long rootId, int size, String cursor, String mode,
                              Supplier<List<Comment>> loader) {
        if (!enabled) {
            return mutable(loader.get());
        }
        String key = null;
        try {
            key = pageKey(mediaId, rootId, size, cursor, mode, currentGeneration(mediaId));
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                List<Comment> cached = objectMapper.readValue(json, LIST_TYPE);
                hitCounter.increment();
                return mutable(cached);
            }
        } catch (Exception e) {
            errorCounter.increment();
            log.warn("评论页缓存读取失败（fail-open 回源 DB）: mediaId={}, {}", mediaId, e.toString());
        }
        List<Comment> rows = mutable(loader.get());
        if (key != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(rows), ttl);
                missCounter.increment();
            } catch (Exception e) {
                errorCounter.increment();
                log.warn("评论页缓存回填失败（fail-open，下个请求继续回源）: mediaId={}, {}",
                        mediaId, e.toString());
            }
        }
        return rows;
    }

    /**
     * 落库成功后调用：推进该 mediaId 的 generation，令其所有缓存页立即失效。
     *
     * <p>不做 {@code KEYS/SCAN} 删除——旧页靠 TTL 自净。失效失败只 WARN：最坏结果是
     * 列表滞后一个 TTL，不影响落库正确性。</p>
     */
    public void evict(String mediaId) {
        if (!enabled || mediaId == null || mediaId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().increment(genKey(mediaId));
            evictCounter.increment();
        } catch (Exception e) {
            errorCounter.increment();
            log.warn("评论页缓存失效失败（旧页最多滞后一个 TTL）: mediaId={}, {}", mediaId, e.toString());
        }
    }

    /** generation 读取失败按 0 处理，等价于「沿用当前代」，缓存仍可读但可能滞后。 */
    private String currentGeneration(String mediaId) {
        String gen = redis.opsForValue().get(genKey(mediaId));
        return gen == null || gen.isBlank() ? "0" : gen;
    }

    static String pageKey(String mediaId, long rootId, int size, String cursor, String mode, String generation) {
        return KEY_PREFIX + digest(mediaId) + ":g" + generation
                + ":" + rootId + ":" + size + ":" + (mode == null ? "newest" : mode.toLowerCase())
                + ":" + digest(cursor == null ? "" : cursor);
    }

    static String genKey(String mediaId) {
        return GEN_PREFIX + digest(mediaId == null ? "" : mediaId);
    }

    /** SHA-256 前 16 位 hex：64bit 空间足够区分页维度，且不会把 mediaId 原样写进 Redis。 */
    static String digest(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256，无法构造评论页缓存 Key", e);
        }
    }

    /** enrichLikeCounts 会 items.set(...)，因此缓存返回值必须可变。 */
    private static List<Comment> mutable(List<Comment> rows) {
        return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }
}
