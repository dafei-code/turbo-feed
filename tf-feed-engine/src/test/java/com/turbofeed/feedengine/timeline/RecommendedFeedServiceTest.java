package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A6 回归护栏：推荐流缓存引入「全局版本号 + 用户身份维度」后的正确性契约。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定三类最易回归且后果最严重的行为：</p>
 * <ul>
 *   <li>① <b>跨用户串号防护</b>（最严重）：登录用户的缓存 Key 必须含 userId，
 *       不同用户绝不共用 Key；匿名 Key 不含 userId（可跨用户共享）。</li>
 *   <li>② <b>失效方式</b>：{@code invalidate()} 只 {@code INCR} 版本号，
 *       不再枚举 {@code DEL}——这是「登录用户 Key 数量无界」下唯一可行的失效方式。</li>
 *   <li>③ <b>降级口径</b>：版本号读不到（Redis 不可达）时跳过缓存直接实时读，
 *       且绝不写入任何缓存 Key（避免抖动窗口内不同用户结果落到同一版本 Key 上）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendedFeedServiceTest {

    @Mock FeedTimelineStore feedTimelineStore;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecommendedFeedService service() {
        return new RecommendedFeedService(feedTimelineStore, redisTemplate, objectMapper);
    }

    /** 构造一条契约条目：postId 即 timelineKey() 的取值来源（mediaId 为 null 时回退到它）。 */
    private static FeedItemView item(String postId) {
        return new FeedItemView(postId, "media/1/x.jpg", "http://x/1.jpg", null,
                FeedItemView.STATUS_APPROVED, null, "caption", null, null);
    }

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ===== ① 跨用户串号防护 =====

    @Test
    void loginUserCacheKeyContainsUserId_andDifferentUsersDoNotShareKey() {
        stubValueOps();
        // 注意桩顺序：Mockito 后定义者优先，通配桩必须先于具体桩，否则版本号读取被 null 覆盖
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.get("tf:feed:rec:version")).thenReturn("7");
        List<FeedItemView> fresh = List.of(item("post/1/a"));
        when(feedTimelineStore.readPage(eq("1001"), eq(0), eq(20))).thenReturn(fresh);

        service().recommended(0, 20, "1001");

        // 写入的缓存 Key 必须含用户身份
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), any());
        String key = keyCaptor.getValue();
        assertTrue(key.contains("1001"), "登录用户缓存 Key 必须含 userId，实际=" + key);
        assertTrue(key.contains(":u:"), "登录用户 Key 应走用户维度命名空间，实际=" + key);
        // 版本号必须嵌进 Key，否则 invalidate() 无法让它失效
        assertTrue(key.startsWith("tf:feed:rec:7:"), "缓存 Key 必须嵌全局版本号，实际=" + key);
    }

    @Test
    void twoDifferentUsersWriteToDifferentKeys_noCrossContamination() {
        stubValueOps();
        // 通配桩先定义、具体桩后定义（Mockito 后定义者优先）
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.get("tf:feed:rec:version")).thenReturn("3");
        when(feedTimelineStore.readPage(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        service().recommended(0, 20, "1001");
        service().recommended(0, 20, "2002");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(keys.capture(), anyString(), any());
        List<String> written = keys.getAllValues();
        assertNotEquals(written.get(0), written.get(1),
                "不同用户的缓存 Key 必须不同，否则用户 B 会读到用户 A 的个性化排序（跨用户串号）");
        assertTrue(written.get(0).contains("1001") && written.get(1).contains("2002"),
                "两次写入应分别归属各自用户，实际=" + written);
    }

    @Test
    void anonymousCacheKeyHasNoUserId_andIsSharedAcrossRequests() {
        stubValueOps();
        // 通配桩先定义、具体桩后定义（Mockito 后定义者优先）
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.get("tf:feed:rec:version")).thenReturn("5");
        when(feedTimelineStore.readPage(isNull(), eq(0), eq(20))).thenReturn(List.of());

        service().recommended(0, 20, null);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), any());
        String key = keyCaptor.getValue();
        assertFalse(key.contains(":u:"), "匿名 Key 不应含用户命名空间，实际=" + key);
        assertEquals("tf:feed:rec:5:0:20", key, "匿名 Key 应为「版本:页码:页大小」，实际=" + key);
    }

    @Test
    void cacheHitSkipsTimelineRead() {
        stubValueOps();
        when(valueOps.get("tf:feed:rec:version")).thenReturn("9");
        String hitKey = "tf:feed:rec:9:u:1001:0:20";
        // 缓存串按契约字段序列化：timelineKey() 是派生方法、不进 JSON，身份由 postId 承载
        when(valueOps.get(hitKey)).thenReturn("[{\"postId\":\"post/1/cached\",\"mediaId\":\"media/1/x.jpg\"}]");

        List<FeedItemView> got = service().recommended(0, 20, "1001");

        assertEquals(1, got.size());
        assertEquals("post/1/cached", got.get(0).timelineKey());
        verify(feedTimelineStore, never()).readPage(any(), anyInt(), anyInt());
        verify(valueOps, never()).set(anyString(), anyString(), any());
    }

    // ===== ② 失效方式：INCR 版本号，不枚举 DEL =====

    @Test
    void invalidateIncrsGlobalVersion_andDoesNotEnumerateDelete() {
        stubValueOps();
        when(valueOps.increment("tf:feed:rec:version")).thenReturn(8L);

        service().invalidate();

        verify(valueOps).increment("tf:feed:rec:version");
        // 登录用户 Key 数量无界，枚举删除不可行；改造后不得再出现按 Key 删除
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void invalidateFailureIsFailOpen_andDoesNotThrow() {
        stubValueOps();
        when(valueOps.increment("tf:feed:rec:version")).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service().invalidate(),
                "失效失败必须 fail-open（退化为 TTL 兜底），不得把异常抛给时间线写入主流程");
    }

    // ===== ③ 降级口径：版本号不可得则跳过缓存 =====

    @Test
    void versionUnavailable_skipsCacheEntirely_andReadsRealtime() {
        stubValueOps();
        when(valueOps.get("tf:feed:rec:version")).thenThrow(new RuntimeException("redis down"));
        when(feedTimelineStore.readPage(eq("1001"), eq(0), eq(20)))
                .thenReturn(List.of(item("post/1/live")));

        List<FeedItemView> got = service().recommended(0, 20, "1001");

        assertEquals("post/1/live", got.get(0).timelineKey(), "版本号不可得时必须回落实时读时间线");
        // 关键：不得写入任何缓存 Key——抖动窗口内写缓存可能让不同用户结果落到同一版本 Key
        verify(valueOps, never()).set(anyString(), anyString(), any());
    }

    // ===== P3-PR07：keyset 游标分页 =====

    @Test
    void recommendedPage_cursorMode_computesNextCursor_andCachesByCursorKey() {
        stubValueOps();
        when(valueOps.get("tf:feed:rec:version")).thenReturn("11");
        FeedItemView last = item("post/1/b");
        List<FeedItemView> fresh = List.of(item("post/1/a"), last);
        when(feedTimelineStore.readPage(eq("1001"), eq(20), any(FeedCursor.class))).thenReturn(fresh);
        // 末条入流 score（经反查索引 + ZSCORE）决定 nextCursor
        when(feedTimelineStore.inflowScoreOf("post/1/b")).thenReturn(1700000000999L);

        FeedCursor cursor = FeedCursor.of(1700000000000.0, "post/1/prev");
        FeedRecommendedPage page = service().recommendedPage(0, 20, "1001", cursor);

        // 末条入流 score → 编码进不透明 nextCursor（前端原样回传）
        assertEquals(FeedCursor.of(1700000000999.0, "post/1/b").encode(), page.nextCursor());
        assertFalse(page.hasMore(), "2 条 < limit 20 → 无更多（hasMore 语义由 items.size()==limit 决定）");
        // 缓存 Key 必须含 :cursor: 维度，与页码模式互不串
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), any());
        assertTrue(keyCaptor.getValue().contains(":cursor:" + cursor.encode()),
                "游标模式缓存 Key 必须含 cursor 维度，实际=" + keyCaptor.getValue());
    }

    @Test
    void recommendedPage_nullCursor_degeneratesToPageMode_withoutCursor() {
        stubValueOps();
        when(valueOps.get("tf:feed:rec:version")).thenReturn("4");
        when(feedTimelineStore.readPage(eq("1001"), eq(0), eq(20))).thenReturn(List.of(item("post/1/a")));

        FeedRecommendedPage page = service().recommendedPage(0, 20, "1001", null);

        assertEquals("", page.nextCursor(), "页码模式不产出游标");
        assertFalse(page.hasMore(), "1 条 < limit 20 → 无更多");
        // 页码模式的缓存 Key 不应混入 :cursor: 维度（与游标模式隔离）
        verify(valueOps).set(argThat(k -> !k.contains(":cursor:")), anyString(), any());
    }
}
