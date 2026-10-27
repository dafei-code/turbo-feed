package com.turbofeed.shared.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 靶心：traceId 的生成与入站校验（{@link TraceIds}）。
 *
 * <p>本类只有纯函数，但它的两条约束都是<b>安全性质</b>而非功能性质，
 * 一旦被「顺手放宽」就不会有任何功能测试变红——所以在这里写死：</p>
 * <ul>
 *   <li>入站值必须过白名单（防日志注入：{@code \n} 能伪造整条日志行）；</li>
 *   <li>入站值必须有长度上限（防超长 header 撑爆日志与采集链路）。</li>
 * </ul>
 */
class TraceIdsTest {

    @Test
    @DisplayName("生成：32 位十六进制，且批量不重复")
    void generate_isHex32AndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = TraceIds.generate();
            assertEquals(32, id.length(), "长度应为 UUID 去分隔符后的 32");
            assertTrue(id.matches("[0-9a-f]{32}"), "必须是十六进制: " + id);
            assertTrue(seen.add(id), "traceId 重复会让两笔请求的日志混成一条，比没有更糟");
        }
    }

    @Test
    @DisplayName("校验：合规值原样放行（首尾空白先裁剪）")
    void sanitize_acceptsValidValues() {
        assertEquals("abc123", TraceIds.sanitize("abc123"));
        assertEquals("a.b_c-d", TraceIds.sanitize("a.b_c-d"));
        assertEquals("abc123", TraceIds.sanitize("  abc123  "), "上游可能带空格，裁剪后仍合规");
    }

    @Test
    @DisplayName("校验：拒绝换行/回车（日志注入）")
    void sanitize_rejectsLogInjection() {
        // 这两个值一旦进 MDC，日志里会出现一整条伪造行——可以伪造成「审核通过」之类的审计记录。
        assertNull(TraceIds.sanitize("abc\n2026-10-27 INFO 审核通过 mediaId=1"));
        assertNull(TraceIds.sanitize("abc\r\ndef"));
        assertNull(TraceIds.sanitize("abc\tdef"));
    }

    @Test
    @DisplayName("校验：拒绝超长值（无界长度撑大日志）")
    void sanitize_rejectsOversize() {
        assertNull(TraceIds.sanitize("a".repeat(65)), "超过 64 必须拒绝");
        assertEquals(64, TraceIds.sanitize("a".repeat(64)).length(), "恰好 64 应放行（边界内）");
    }

    @Test
    @DisplayName("校验：空值与非白名单字符一律判为不可用")
    void sanitize_rejectsBlankAndIllegalChars() {
        assertNull(TraceIds.sanitize(null));
        assertNull(TraceIds.sanitize(""));
        assertNull(TraceIds.sanitize("   "));
        // 常见注入载荷与 URL 残留：
        assertNull(TraceIds.sanitize("<script>"));
        assertNull(TraceIds.sanitize("a;b"));
        assertNull(TraceIds.sanitize("a/b"));
        assertNull(TraceIds.sanitize("中文"));
    }

    @Test
    @DisplayName("常量：header 名与 MDC 键是对外契约，不得随手改")
    void constants_areStableContract() {
        // header 名变了 → 网关与引擎之间的透传静默失效（两边都还能跑，只是日志串不起来）。
        assertEquals("X-Trace-Id", TraceIds.HEADER);
        // MDC 键必须与日志格式里的 %X{traceId} 一致。
        assertEquals("traceId", TraceIds.MDC_KEY);
    }
}
