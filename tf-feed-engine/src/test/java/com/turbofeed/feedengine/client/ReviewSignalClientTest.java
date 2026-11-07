package com.turbofeed.feedengine.client;

import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.shared.model.PlayableView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewSignalClient} 隔离单测（P2-1 审核信号消费端，与网关 {@code ModerationSignalService} 配对）。
 *
 * <p>纯 Mockito 单元，不依赖 Redis：mock {@link StringRedisTemplate#opsForHash()} 的 {@link HashOperations}，
 * 断言召回过滤（内容 INTERCEPT/MONITOR、作者 BANNED/低健康分）、播放前复校（仅 INTERCEPT / 原因细分）、
 * 排序降权（作者级 DEMOTE 档 = demoteScale，内容级 action=DEMOTE = contentDemoteScale），
 * 以及 fail-open（读异常 / 缺失 / blank 不拦截不降权）。</p>
 *
 * <p><b>ADR-0014 回归锁</b>：内容级 DEMOTE 绝不进召回拦截集、绝不打断播放，只在精排降权。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReviewSignalClientTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hash;

    private static FeedModerationProperties props() {
        FeedModerationProperties p = new FeedModerationProperties();
        p.setNamespace("tf:mod:");
        p.setDropBelowScore(60);
        p.setDemoteScale(0.5d);
        p.setEnableContentDemotion(true);
        p.setContentDemoteScale(0.7d);
        return p;
    }

    private ReviewSignalClient client() {
        MeterRegistry registry = new SimpleMeterRegistry();
        return new ReviewSignalClient(redis, props(), registry);
    }

    // ---- 内容召回过滤 ----

    @Test
    void blockedContentKeys_filtersInterceptAndMonitor_notPassOrMiss() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:media:p1", "action")).thenReturn("INTERCEPT");
        when(hash.get("tf:mod:media:p2", "action")).thenReturn("MONITOR");
        when(hash.get("tf:mod:media:p3", "action")).thenReturn("PASS");
        when(hash.get("tf:mod:media:p4", "action")).thenReturn(null);
        when(hash.get("tf:mod:media:p5", "action")).thenReturn("DEMOTE");
        ReviewSignalClient c = client();

        Set<String> blocked = c.blockedContentKeys(Set.of("p1", "p2", "p3", "p4", "p5"));
        // DEMOTE 是轻降权，不是公域隔离——不得被召回层硬剔除
        assertEquals(Set.of("p1", "p2"), blocked);
    }

    @Test
    void blockedContentKeys_emptyInput_returnsEmpty() {
        ReviewSignalClient c = client();
        assertTrue(c.blockedContentKeys(Set.of()).isEmpty());
        assertTrue(c.blockedContentKeys(null).isEmpty());
    }

    @Test
    void blockedContentKeys_whenRedisThrows_returnsEmpty_failOpen() {
        when(redis.<Object, Object>opsForHash()).thenThrow(new RuntimeException("redis down"));
        ReviewSignalClient c = client();

        // 不抛异常，返回空集（fail-open）
        assertTrue(c.blockedContentKeys(Set.of("p1")).isEmpty());
    }

    // ---- 作者召回过滤 ----

    private static Map<Object, Object> accountMap(String tier, String score) {
        Map<Object, Object> m = new HashMap<>();
        if (tier != null) {
            m.put("tier", tier);
        }
        if (score != null) {
            m.put("score", score);
        }
        return m;
    }

    @Test
    void blockedAuthorIds_blocksBannedAndLowScore_notHealthyOrMiss() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries("tf:mod:account:u1")).thenReturn(accountMap("BANNED", "0"));
        when(hash.entries("tf:mod:account:u2")).thenReturn(accountMap("RESTRICT", "50")); // <60
        when(hash.entries("tf:mod:account:u3")).thenReturn(accountMap("NORMAL", "75"));   // >=60
        when(hash.entries("tf:mod:account:u4")).thenReturn(new HashMap<>());             // 缺失
        ReviewSignalClient c = client();

        Set<String> blocked = c.blockedAuthorIds(Set.of("u1", "u2", "u3", "u4"));
        assertEquals(Set.of("u1", "u2"), blocked);
    }

    // ---- 播放前复校：仅 INTERCEPT ----

    @Test
    void interceptedContentKeys_onlyIntercept_notMonitorOrDemote() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:media:p1", "action")).thenReturn("INTERCEPT");
        when(hash.get("tf:mod:media:p2", "action")).thenReturn("MONITOR");
        when(hash.get("tf:mod:media:p3", "action")).thenReturn("PASS");
        when(hash.get("tf:mod:media:p4", "action")).thenReturn("DEMOTE");
        ReviewSignalClient c = client();

        Set<String> intercepted = c.interceptedContentKeys(Set.of("p1", "p2", "p3", "p4"));
        assertEquals(Set.of("p1"), intercepted);
    }

    // ---- 播放前复校：作者拦截原因细分 ----

    @Test
    void authorBlockReason_distinguishesBannedAndLowScore() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries("tf:mod:account:a1")).thenReturn(accountMap("BANNED", "0"));
        when(hash.entries("tf:mod:account:a2")).thenReturn(accountMap("RESTRICT", "50"));
        when(hash.entries("tf:mod:account:a3")).thenReturn(accountMap("NORMAL", "75"));
        when(hash.entries("tf:mod:account:a4")).thenReturn(new HashMap<>());
        ReviewSignalClient c = client();

        assertEquals(PlayableView.REASON_ACCOUNT_BANNED, c.authorBlockReason("a1"));
        assertEquals(PlayableView.REASON_ACCOUNT_LOW_SCORE, c.authorBlockReason("a2"));
        assertNull(c.authorBlockReason("a3"));
        assertNull(c.authorBlockReason("a4"));
    }

    @Test
    void authorBlockReason_blankInput_returnsNull() {
        ReviewSignalClient c = client();
        assertNull(c.authorBlockReason(null));
        assertNull(c.authorBlockReason("  "));
    }

    // ---- 排序降权：作者级 ----

    @Test
    void healthScaleOf_demoteTier_returnsDemoteScale_othersReturnOne() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries("tf:mod:account:d1")).thenReturn(accountMap("DEMOTE", "70"));
        when(hash.entries("tf:mod:account:n1")).thenReturn(accountMap("NORMAL", "90"));
        when(hash.entries("tf:mod:account:n2")).thenReturn(new HashMap<>()); // 缺失 → 1.0
        ReviewSignalClient c = client();

        assertEquals(0.5d, c.healthScaleOf("d1"), 1e-9);
        assertEquals(1.0d, c.healthScaleOf("n1"), 1e-9);
        assertEquals(1.0d, c.healthScaleOf("n2"), 1e-9);
    }

    @Test
    void healthScaleOf_blankInput_returnsOne() {
        ReviewSignalClient c = client();
        assertEquals(1.0d, c.healthScaleOf(null), 1e-9);
        assertEquals(1.0d, c.healthScaleOf(""), 1e-9);
    }

    @Test
    void healthScaleOf_whenEntriesThrows_returnsOne_failOpen() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries(anyString())).thenThrow(new RuntimeException("redis down"));
        ReviewSignalClient c = client();

        assertEquals(1.0d, c.healthScaleOf("x"), 1e-9);
    }

    // ---- 排序降权：内容级（ADR-0014） ----

    @Test
    void contentDemotionScaleOf_demoteAction_returnsContentScale_othersReturnOne() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.get("tf:mod:media:d1", "action")).thenReturn("DEMOTE");
        when(hash.get("tf:mod:media:m1", "action")).thenReturn("MONITOR");
        when(hash.get("tf:mod:media:i1", "action")).thenReturn("INTERCEPT");
        when(hash.get("tf:mod:media:p1", "action")).thenReturn("PASS");
        when(hash.get("tf:mod:media:n1", "action")).thenReturn(null); // 无信号
        ReviewSignalClient c = client();

        assertEquals(0.7d, c.contentDemotionScaleOf("d1"), 1e-9);
        assertEquals(1.0d, c.contentDemotionScaleOf("m1"), 1e-9);
        assertEquals(1.0d, c.contentDemotionScaleOf("i1"), 1e-9);
        assertEquals(1.0d, c.contentDemotionScaleOf("p1"), 1e-9);
        assertEquals(1.0d, c.contentDemotionScaleOf("n1"), 1e-9);
    }

    @Test
    void contentDemotionScaleOf_blankOrSwitchOff_returnsOne_withoutRedisRead() {
        ReviewSignalClient c = client();
        assertEquals(1.0d, c.contentDemotionScaleOf(null), 1e-9);
        assertEquals(1.0d, c.contentDemotionScaleOf("  "), 1e-9);

        FeedModerationProperties off = props();
        off.setEnableContentDemotion(false);
        ReviewSignalClient disabled = new ReviewSignalClient(redis, off, new SimpleMeterRegistry());
        assertEquals(1.0d, disabled.contentDemotionScaleOf("any"), 1e-9);

        // 开关关闭时不应打 Redis（灰度回滚口径）
        verify(redis, never()).opsForHash();
    }

    @Test
    void contentDemotionScaleOf_whenRedisThrows_returnsOne_failOpen() {
        when(redis.<Object, Object>opsForHash()).thenThrow(new RuntimeException("redis down"));
        ReviewSignalClient c = client();

        assertEquals(1.0d, c.contentDemotionScaleOf("x"), 1e-9);
    }
}
