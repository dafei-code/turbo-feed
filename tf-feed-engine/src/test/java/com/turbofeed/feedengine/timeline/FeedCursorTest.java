package com.turbofeed.feedengine.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-PR07 回归护栏：feed/recommended 的 keyset 游标 {@link FeedCursor} 编解码与边界语义。
 *
 * <p>纯逻辑、无 Redis/Spring 依赖。锁定的关键是：游标锚定<b>入流 score</b>而非上传时刻，
 * 且 {@link FeedCursor#excludes(double, String)} 必须保证「翻页严格单调前进、跨页不重复」——
 * 即游标所指及之后（更晚 score、或同分且身份不早于游标）一律被排除，严格更早者保留。</p>
 */
class FeedCursorTest {

    @Test
    void encodeDecode_roundTrip_preservesScoreAndKey() {
        FeedCursor c = FeedCursor.of(1700000000123.0, "post/1/a");
        String token = c.encode();
        assertFalse(token.isBlank(), "编码后应为不透明非空 token（前端原样回传）");

        FeedCursor decoded = FeedCursor.decode(token);
        assertNotNull(decoded);
        assertEquals(1700000000123.0, decoded.score(), 0.0001);
        assertEquals("post/1/a", decoded.timelineKey());
    }

    @Test
    void decode_blankOrNull_returnsNull_asFirstPage() {
        assertNull(FeedCursor.decode(null));
        assertNull(FeedCursor.decode(""));
        assertNull(FeedCursor.decode("   "));
    }

    @Test
    void decode_garbageToken_returnsNull_failOpen() {
        // 前端若篡改/截断 token，不能抛异常，按首页处理（fail-open）
        assertNull(FeedCursor.decode("not-a-real-token!!"));
    }

    @Test
    void encode_blankKey_returnsEmpty_firstPageMarker() {
        assertEquals("", FeedCursor.of(123.0, "").encode());
        assertEquals("", FeedCursor.of(123.0, null).encode());
    }

    @Test
    void excludes_scoreLaterThanCursor_isExcluded() {
        FeedCursor c = FeedCursor.of(100.0, "post/1/a");
        assertTrue(c.excludes(200.0, "post/1/b"), "更晚入流的内容必须跳过（已下发或游标之后）");
        assertFalse(c.excludes(50.0, "post/1/b"), "严格更早入流的内容必须保留");
    }

    @Test
    void excludes_equalScore_identityOrdering() {
        FeedCursor c = FeedCursor.of(100.0, "post/1/a");
        assertTrue(c.excludes(100.0, "post/1/a"), "游标所指自身必须排除");
        assertTrue(c.excludes(100.0, "post/1/z"), "同分且身份更晚必须排除");
        assertFalse(c.excludes(100.0, "post/1/0"), "同分但身份更早必须保留（防止漏条）");
    }

    @Test
    void excludes_nullKey_isAlwaysExcluded() {
        FeedCursor c = FeedCursor.of(100.0, "post/1/a");
        assertTrue(c.excludes(50.0, null), "无时间线身份的候选无法定位，必须排除");
    }
}
