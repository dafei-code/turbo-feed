package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModerationSignalService} 隔离单测（P2-1 审核信号外供 KV）。
 *
 * <p>纯 Mockito 单元，不依赖 Redis / MySQL：mock {@link StringRedisTemplate#opsForHash()} 与
 * {@link HashOperations}，断言写路径的 key/value/TTL、读路径的 miss/hit、batch 跳过 miss，
 * 以及 fail-open（写异常不向外抛）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModerationSignalServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, String, String> hash;

    // ---- 构造辅助 ----

    private static MediaProperties mediaProps(String ns, long mediaTtl, long accountTtl, long reporterTtl) {
        MediaProperties mp = new MediaProperties();
        MediaProperties.Signal s = new MediaProperties.Signal();
        s.setNamespace(ns);
        s.setMediaTtlSeconds(mediaTtl);
        s.setAccountTtlSeconds(accountTtl);
        s.setReporterTtlSeconds(reporterTtl);
        mp.setSignal(s);
        return mp;
    }

    private ModerationSignalService service(long mediaTtl, long accountTtl, long reporterTtl) {
        return new ModerationSignalService(redis, mediaProps("tf:mod:", mediaTtl, accountTtl, reporterTtl));
    }

    // ---- 内容处置信号 ----

    @Test
    void publishMediaDisposition_writesHashAndSetsTtl() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        svc.publishMediaDisposition("p1", Disposition.INTERCEPT, ViolationSeverity.HIGH, ViolationSource.HUMAN_REVIEW);

        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(hash).putAll(eq("tf:mod:media:p1"), cap.capture());
        Map<String, String> m = cap.getValue();
        assertEquals("INTERCEPT", m.get("action"));
        assertEquals("HIGH", m.get("severity"));
        assertEquals("HUMAN_REVIEW", m.get("source"));
        assertTrue(m.containsKey("ts"));

        // TTL>0 → 显式 expire
        verify(redis).expire(eq("tf:mod:media:p1"), eq(Duration.ofSeconds(2592000L)));
    }

    @Test
    void publishMediaDisposition_blankPostId_doesNotWrite() {
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        svc.publishMediaDisposition("   ", Disposition.INTERCEPT, ViolationSeverity.HIGH, ViolationSource.HUMAN_REVIEW);

        // 提前 return，未触碰 Redis
        verify(hash, never()).putAll(any(), any());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void getMediaDisposition_miss_returnsNull() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:media:p2", "action")).thenReturn(null);
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        assertNull(svc.getMediaDisposition("p2"));
    }

    @Test
    void getMediaDisposition_blankPostId_returnsNull() {
        ModerationSignalService svc = service(2592000L, 0L, 0L);
        assertNull(svc.getMediaDisposition(""));
    }

    @Test
    void getMediaDisposition_hit_returnsView() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:media:p3", "action")).thenReturn("MONITOR");
        when(hash.get("tf:mod:media:p3", "severity")).thenReturn("MID");
        when(hash.get("tf:mod:media:p3", "source")).thenReturn("MACHINE");
        when(hash.get("tf:mod:media:p3", "ts")).thenReturn("1700000000");
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        ModerationSignalView.DispositionView v = svc.getMediaDisposition("p3");
        assertEquals("MONITOR", v.action());
        assertEquals("MID", v.severity());
        assertEquals("MACHINE", v.source());
        assertEquals(1700000000L, v.ts());
    }

    @Test
    void batchGetMediaDisposition_excludesMisses_andEmptyList() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        // a: 命中
        when(hash.get("tf:mod:media:a", "action")).thenReturn("INTERCEPT");
        when(hash.get("tf:mod:media:a", "severity")).thenReturn("HIGH");
        when(hash.get("tf:mod:media:a", "source")).thenReturn("MACHINE");
        when(hash.get("tf:mod:media:a", "ts")).thenReturn("1");
        // b: 缺失
        when(hash.get("tf:mod:media:b", "action")).thenReturn(null);
        // c: 命中
        when(hash.get("tf:mod:media:c", "action")).thenReturn("PASS");
        when(hash.get("tf:mod:media:c", "severity")).thenReturn("LOW");
        when(hash.get("tf:mod:media:c", "source")).thenReturn("HUMAN_REPORT");
        when(hash.get("tf:mod:media:c", "ts")).thenReturn("2");
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        Map<String, ModerationSignalView.DispositionView> result =
                svc.batchGetMediaDisposition(List.of("a", "b", "c"));
        assertEquals(2, result.size());
        assertTrue(result.containsKey("a"));
        assertTrue(result.containsKey("c"));
        assertFalse(result.containsKey("b"));
        assertEquals("INTERCEPT", result.get("a").action());
        assertEquals("PASS", result.get("c").action());

        // null / 空列表 → 空 map
        assertTrue(svc.batchGetMediaDisposition(null).isEmpty());
        assertTrue(svc.batchGetMediaDisposition(List.of()).isEmpty());
    }

    // ---- 账号健康分 ----

    @Test
    void publishAccountHealth_writesHash_noTtlWhenZero() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        ModerationSignalService svc = service(2592000L, 0L, 0L); // accountTtl=0 → 不过期

        svc.publishAccountHealth(1001, 80, "GOOD");

        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(hash).putAll(eq("tf:mod:account:1001"), cap.capture());
        Map<String, String> m = cap.getValue();
        assertEquals("80", m.get("score"));
        assertEquals("GOOD", m.get("tier"));
        assertTrue(m.containsKey("ts"));

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void publishAccountHealth_withTtl_setsExpire() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        ModerationSignalService svc = service(2592000L, 3600L, 0L); // accountTtl=3600

        svc.publishAccountHealth(1002, 50, "WATCH");

        verify(hash).putAll(eq("tf:mod:account:1002"), any(Map.class));
        verify(redis).expire(eq("tf:mod:account:1002"), eq(Duration.ofSeconds(3600L)));
    }

    @Test
    void getAccountHealth_hit_returnsView() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:account:7", "score")).thenReturn("42");
        when(hash.get("tf:mod:account:7", "tier")).thenReturn("BAD");
        when(hash.get("tf:mod:account:7", "ts")).thenReturn("99");
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        ModerationSignalView.HealthView v = svc.getAccountHealth(7);
        assertEquals(42, v.score());
        assertEquals("BAD", v.tier());
        assertEquals(99L, v.ts());
    }

    @Test
    void getAccountHealth_miss_returnsNull() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:account:8", "score")).thenReturn(null);
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        assertNull(svc.getAccountHealth(8));
    }

    // ---- 举报人信用 ----

    @Test
    void publishReporterCredit_writesHash_noTtlWhenZero() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        ModerationSignalService svc = service(2592000L, 0L, 0L); // reporterTtl=0 → 不过期

        svc.publishReporterCredit(9, 10, true);

        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(hash).putAll(eq("tf:mod:reporter:9"), cap.capture());
        Map<String, String> m = cap.getValue();
        assertEquals("10", m.get("score"));
        assertEquals("true", m.get("banned"));
        assertTrue(m.containsKey("ts"));

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void getReporterCredit_hit_returnsView() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:reporter:9", "score")).thenReturn("10");
        when(hash.get("tf:mod:reporter:9", "banned")).thenReturn("true");
        when(hash.get("tf:mod:reporter:9", "ts")).thenReturn("5");
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        ModerationSignalView.CreditView v = svc.getReporterCredit(9);
        assertEquals(10, v.score());
        assertTrue(v.banned());
        assertEquals(5L, v.ts());
    }

    // ---- fail-open ----

    @Test
    void publishMediaDisposition_whenRedisThrows_doesNotPropagate() {
        when(redis.<String, String>opsForHash()).thenThrow(new RuntimeException("redis down"));
        ModerationSignalService svc = service(2592000L, 0L, 0L);

        // 不应抛异常（fail-open）
        svc.publishMediaDisposition("p9", Disposition.PASS, ViolationSeverity.LOW, ViolationSource.MACHINE);
    }
}
