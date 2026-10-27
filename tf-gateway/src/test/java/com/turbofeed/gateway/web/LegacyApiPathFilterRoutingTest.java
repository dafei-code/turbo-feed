package com.turbofeed.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 老路径兼容层的端到端验证（P2-3）：<b>重写后能不能真的命中处理器</b>。
 *
 * <p><b>为什么必须有这一层测试</b>：{@link LegacyApiPathFilter} 把请求 URI 换成了版本化路径，
 * 但「Spring MVC 是否接受这个换了 URI 的包装请求并正确路由」是实现里最可能悄悄失败的一环——
 * 过滤器单测（只验证头与返回值）永远测不到它。反过来，如果这里不过，症状是
 * <b>所有老客户端一次性全挂</b>，而且是 404 而不是报错，最难在发布时第一时间归因。</p>
 *
 * <p>用 {@code standaloneSetup} 起最小 MVC（不加载 Spring 上下文、不需要 DB/Redis），
 * 只挂一个 demo 控制器 + 真实过滤器，验证四条链路：老路径可路由并带弃用头、
 * 新路径原样放行且无弃用头、非 {@code /api} 路径不受影响、不会被二次加前缀。</p>
 */
class LegacyApiPathFilterRoutingTest {

    /** 只用于本测试的路由靶子：真实控制器在 controller 包，这里不引入任何业务依赖。 */
    @RestController
    @RequestMapping(ApiVersion.V1 + "/demo")
    static class DemoController {
        @GetMapping("/ping")
        Map<String, Object> ping() {
            return Map.of("ok", true);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new DemoController())
                .addFilters(new LegacyApiPathFilter())
                .build();
    }

    @Test
    void 老路径被重写后仍能命中处理器_并带弃用头() throws Exception {
        mvc().perform(get("/api/demo/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                // RFC 9745：@<epoch 秒>，不是 true/false
                .andExpect(header().string(LegacyApiPathFilter.DEPRECATION_HEADER, startsWith("@")))
                // 告诉客户端该换成哪个 URL
                .andExpect(header().string(LegacyApiPathFilter.LINK_HEADER,
                        containsString("</api/v1/demo/ping>; rel=\"successor-version\"")));
    }

    @Test
    void 版本化路径原样放行_不带弃用头() throws Exception {
        mvc().perform(get("/api/v1/demo/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(header().doesNotExist(LegacyApiPathFilter.DEPRECATION_HEADER));
    }

    @Test
    void 不会被二次加前缀() throws Exception {
        // /api/v1x/... 不是合法版本号：应被当作老路径处理（重写成 /api/v1/v1x/...，于是 404）。
        // 关键是"不能变成 /api/v1/v1x"，也不能命中 /api/v1/demo。
        mvc().perform(get("/api/v1x/demo/ping"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非api路径不受影响() throws Exception {
        mvc().perform(get("/internal/review/ping"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(LegacyApiPathFilter.DEPRECATION_HEADER));
    }

    // ==================== 纯函数：路径改写规则 ====================

    @Test
    void 改写规则_逐类输入() {
        // 老路径 → 版本化
        assertEquals("/api/v1/demo/ping", LegacyApiPathFilter.versionedPathOf("/api/demo/ping"));
        assertEquals("/api/v1/feed/recommended", LegacyApiPathFilter.versionedPathOf("/api/feed/recommended"));
        // 已是版本化 → 不改写（否则叠成 /api/v1/v1/...）
        assertNull(LegacyApiPathFilter.versionedPathOf("/api/v1/demo/ping"));
        assertNull(LegacyApiPathFilter.versionedPathOf("/api/v2/demo/ping"));
        assertNull(LegacyApiPathFilter.versionedPathOf("/api/v1"));
        // 非 /api 命名空间 → 不改写
        assertNull(LegacyApiPathFilter.versionedPathOf("/internal/review/x"));
        assertNull(LegacyApiPathFilter.versionedPathOf("/actuator/prometheus"));
        assertNull(LegacyApiPathFilter.versionedPathOf("/media/1.jpg"));
        // 以 /api 开头但不是路径分段（/apix）→ 不改写
        assertNull(LegacyApiPathFilter.versionedPathOf("/apix/demo"));
        // 裸 /api（无尾斜杠）→ 不改写（改写也没有对应处理器，保持"原来错就原来错"）
        assertNull(LegacyApiPathFilter.versionedPathOf("/api"));
        assertNull(LegacyApiPathFilter.versionedPathOf(null));
    }
}
