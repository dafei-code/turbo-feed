package com.turbofeed.gateway.service.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审优化项④：评论列表页短 TTL 缓存单元测（纯 Mockito + 内存 Map 模拟 Redis，零中间件依赖）。
 *
 * <p>重点锁死四件事：① 开关关闭时零 Redis 流量、零回归；② 缓存命中不打 DB、miss 按 TTL 回填；
 * ③ generation 失效只影响同一 mediaId 的全部页；④ Redis / JSON 异常一律 fail-open 回源。</p>
 */
class CommentPageCacheTest {

    private final Map<String, String> store = new HashMap<>();
    private final AtomicInteger loads = new AtomicInteger();

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper mapper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        store.clear();
        loads.set(0);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            long next = Long.parseLong(store.getOrDefault(key, "0")) + 1L;
            store.put(key, String.valueOf(next));
            return next;
        });
        // 与 Spring Boot 托管 ObjectMapper 口径一致：ISO-8601 字符串而非浮点时间戳
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // when(...)/doAnswer(...) 本身会记一次调用，清掉后 verify 才干净
        clearInvocations(redis, valueOps);
    }

    private CommentPageCache cache(boolean enabled, long ttlMs) {
        return new CommentPageCache(redis, mapper, new SimpleMeterRegistry(), enabled, ttlMs);
    }

    private Supplier<List<Comment>> loader(List<Comment> rows) {
        return () -> {
            loads.incrementAndGet();
            return rows;
        };
    }

    private static Comment comment(long id, int likeCount) {
        return new Comment(id, "media/9/uuid.mp4", 9L, 0L, 0L, "hello",
                CommentStatus.APPROVED, likeCount, Instant.parse("2026-11-16T10:00:00Z"));
    }

    private static List<Comment> rows() {
        return List.of(comment(1L, 0), comment(2L, 3));
    }

    private List<Comment> load(CommentPageCache cache, String mediaId, String cursor, String mode,
                               List<Comment> rows) {
        return cache.load(mediaId, 0L, 20, cursor, mode, loader(rows));
    }

    // ===== 默认关闭：零回归 =====

    @Test
    void disabled_hasZeroRedisTrafficAndLoadsFromDb() {
        CommentPageCache cache = cache(false, 2000);
        List<Comment> got = load(cache, "m1", "", "newest", rows());
        assertEquals(1, loads.get());
        assertEquals(rows(), got);
        verify(redis, never()).opsForValue();
        assertDoesNotThrow(() -> cache.evict("m1"));
        verify(redis, never()).opsForValue();
    }

    // ===== miss / hit / TTL =====

    @Test
    void miss_loadsFromDbAndWritesWithShortTtl() {
        CommentPageCache cache = cache(true, 2000);
        assertEquals(rows(), load(cache, "m1", "", "newest", rows()));
        assertEquals(1, loads.get());
        org.mockito.ArgumentCaptor<Duration> ttl =
                org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), anyString(), ttl.capture());
        assertEquals(Duration.ofSeconds(2), ttl.getValue(), "默认 TTL 必须是短 TTL 2s");
        assertFalse(store.isEmpty());
    }

    @Test
    void hit_skipsLoaderAndPreservesInstantAndStatus() {
        CommentPageCache cache = cache(true, 2000);
        load(cache, "m1", "", "newest", rows());   // miss → 回填
        List<Comment> got = load(cache, "m1", "", "newest", List.of());  // hit
        assertEquals(1, loads.get(), "命中后 DB 加载器不应再执行");
        assertEquals(2, got.size());
        assertEquals(comment(1L, 0), got.get(0));
        assertEquals(comment(2L, 3), got.get(1));
        assertEquals(CommentStatus.APPROVED, got.get(0).status());
        assertEquals(Instant.parse("2026-11-16T10:00:00Z"), got.get(0).createdAt());
    }

    @Test
    void hit_returnsMutableList_forEnrichLikeCounts() {
        CommentPageCache cache = cache(true, 2000);
        load(cache, "m1", "", "newest", rows());
        List<Comment> got = load(cache, "m1", "", "newest", List.of());
        // enrichLikeCounts 会 items.set(i, ...)，不可变列表会直接抛 UnsupportedOperationException
        assertDoesNotThrow(() -> got.set(0, comment(1L, 7)));
        assertEquals(7, got.get(0).likeCount());
    }

    // ===== generation 失效 =====

    @Test
    void evict_invalidatesAllPagesOfSameMediaOnly() {
        CommentPageCache cache = cache(true, 2000);
        load(cache, "m1", "", "newest", rows());            // m1 miss
        load(cache, "m1", "", "newest", List.of());         // m1 hit
        load(cache, "m2", "", "newest", rows());            // m2 miss
        assertEquals(2, loads.get());

        cache.evict("m1");
        load(cache, "m1", "", "newest", rows());            // m1 新代 → miss
        load(cache, "m1", "cur1", "newest", rows());        // 深页同样受影响
        load(cache, "m2", "", "newest", List.of());         // m2 仍命中
        assertEquals(4, loads.get(), "m1 两页失效回源，m2 不受影响");
        assertEquals("1", store.get(CommentPageCache.genKey("m1")), "一次 evict 只推进一代");
    }

    @Test
    void evict_redisFailure_doesNotThrow() {
        CommentPageCache cache = cache(true, 2000);
        doThrow(new RuntimeException("redis down")).when(valueOps).increment(anyString());
        assertDoesNotThrow(() -> cache.evict("m1"), "失效失败只 WARN，最坏滞后一个 TTL");
    }

    // ===== fail-open =====

    @Test
    void redisGetFailure_failsOpenToDb() {
        CommentPageCache cache = cache(true, 2000);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertEquals(rows(), load(cache, "m1", "", "newest", rows()));
        assertEquals(1, loads.get());
    }

    @Test
    void redisSetFailure_failsOpen() {
        CommentPageCache cache = cache(true, 2000);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        assertEquals(rows(), load(cache, "m1", "", "newest", rows()));
        assertEquals(1, loads.get());
    }

    @Test
    void corruptedJson_treatedAsMiss() {
        CommentPageCache cache = cache(true, 2000);
        store.put(CommentPageCache.pageKey("m1", 0L, 20, "", "newest", "0"), "{not-json");
        assertEquals(rows(), load(cache, "m1", "", "newest", rows()));
        assertEquals(1, loads.get(), "坏 JSON 必须按 miss 处理，不能抛给读请求");
    }

    // ===== Key 维度 / 摘要 / TTL 边界 =====

    @Test
    void pageKey_dimensionsAreIsolated() {
        String base = CommentPageCache.pageKey("m1", 0L, 20, "", "newest", "0");
        assertNotEquals(base, CommentPageCache.pageKey("m2", 0L, 20, "", "newest", "0"));
        assertNotEquals(base, CommentPageCache.pageKey("m1", 9L, 20, "", "newest", "0"));
        assertNotEquals(base, CommentPageCache.pageKey("m1", 0L, 50, "", "newest", "0"));
        assertNotEquals(base, CommentPageCache.pageKey("m1", 0L, 20, "cur1", "newest", "0"));
        assertNotEquals(base, CommentPageCache.pageKey("m1", 0L, 20, "", "hot", "0"));
        assertNotEquals(base, CommentPageCache.pageKey("m1", 0L, 20, "", "newest", "1"));
        assertFalse(base.contains("m1"), "mediaId 必须以摘要形式进 Key，不能原样拼接");
    }

    @Test
    void digest_isStableFixedLengthAndDoesNotExposeRawMediaId() {
        String raw = "media/9/2f1c-uuid.mp4";
        String d = CommentPageCache.digest(raw);
        assertEquals(16, d.length());
        assertEquals(d, CommentPageCache.digest(raw));
        assertFalse(d.contains("/"));
        assertNotEquals(d, CommentPageCache.digest(raw + "x"));
    }

    @Test
    void ttl_isClampedToSafeRange() {
        assertEquals(Duration.ofMillis(200), cache(true, 0).ttl());
        assertEquals(Duration.ofMillis(200), cache(true, -1).ttl());
        assertEquals(Duration.ofSeconds(30), cache(true, 999_999).ttl());
        assertEquals(Duration.ofSeconds(5), cache(true, 5000).ttl());
    }
}
