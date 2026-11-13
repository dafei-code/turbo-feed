package com.turbofeed.shared.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4：实例标识的日志注入防护契约。
 *
 * <p>环境变量来自部署平台，和入站 header 一样属于不可信输入：带换行的 {@code POD_NAME}
 * 能伪造出整条日志行，超长值会撑爆采集链路。这里锁死「规范化后一定可安全写入 MDC」。</p>
 */
class InstanceIdsTest {

    @Test
    @DisplayName("current：永不为空，且符合 MDC 白名单与长度上限")
    void current_isAlwaysSafe() {
        String id = InstanceIds.current();
        assertNotNull(id);
        assertTrue(id.matches("[A-Za-z0-9._-]{1,64}"), "实际值: " + id);
    }

    @Test
    @DisplayName("normalize：非法字符替换为 -，而不是丢弃整个值")
    void normalize_replacesUnsafeCharacters() {
        assertEquals("12345-host.local", InstanceIds.normalize("12345@host.local"));
        assertEquals("pod-1", InstanceIds.normalize("pod/1"));
        assertEquals("a-b--INFO-fake", InstanceIds.normalize("a\nb \nINFO fake"));
    }

    @Test
    @DisplayName("normalize：空值兜底、超长截断")
    void normalize_handlesBlankAndOverlong() {
        assertEquals("unknown", InstanceIds.normalize(null));
        assertEquals("unknown", InstanceIds.normalize("   "));
        assertEquals(64, InstanceIds.normalize("x".repeat(500)).length());
    }
}
