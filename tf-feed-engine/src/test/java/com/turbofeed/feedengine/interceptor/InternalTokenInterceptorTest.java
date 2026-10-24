package com.turbofeed.feedengine.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InternalTokenInterceptor} 单测（引擎侧，M0 / R1：内部令牌 fail-fast）。
 *
 * <p>与网关侧 {@code com.turbofeed.gateway.security.InternalTokenInterceptorTest} 对称：
 * 同一份契约，两侧必须一致，否则会出现"一端 fail-fast、另一端裸奔"的错配。</p>
 */
class InternalTokenInterceptorTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        return env;
    }

    private static MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (token != null) {
            req.addHeader(InternalTokenInterceptor.HEADER, token);
        }
        return req;
    }

    // ==================== 构造期 fail-fast（R1 核心） ====================

    @Test
    void construct_noToken_defaultProfile_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new InternalTokenInterceptor("", env()),
                "无 profile 且缺令牌必须启动失败，不能 fail-open");
    }

    @Test
    void construct_noToken_prodProfile_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new InternalTokenInterceptor("", env("prod")),
                "prod 且缺令牌必须启动失败");
    }

    @Test
    void construct_noToken_stagingProfile_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new InternalTokenInterceptor("", env("staging")),
                "staging 不在 dev/local 豁免名单，缺令牌必须启动失败");
    }

    @Test
    void construct_nullToken_defaultProfile_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new InternalTokenInterceptor(null, env()),
                "null 令牌等价于缺失，同样必须启动失败");
    }

    // ==================== dev / local 豁免（克隆即跑） ====================

    @Test
    void construct_noToken_devProfile_allowedAndFailOpen() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("dev"));
        assertTrue(it.preHandle(requestWith(null), new MockHttpServletResponse(), new Object()),
                "dev profile 缺令牌时应 fail-open 放行（克隆即跑）");
    }

    @Test
    void construct_noToken_localProfile_allowedAndFailOpen() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("local"));
        assertTrue(it.preHandle(requestWith(null), new MockHttpServletResponse(), new Object()),
                "local profile 缺令牌时应 fail-open 放行");
    }

    // ==================== 配置令牌后的校验行为 ====================

    @Test
    void preHandle_configuredToken_missingHeader_returns401() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(it.preHandle(requestWith(null), resp, new Object()));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":40101"),
                "引擎 401 响应体须与 Result 形态一致，实际：" + resp.getContentAsString());
    }

    @Test
    void preHandle_configuredToken_wrongHeader_returns401() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(it.preHandle(requestWith("wrong"), resp, new Object()));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatus());
    }

    @Test
    void preHandle_configuredToken_correctHeader_passes() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(it.preHandle(requestWith("secret"), resp, new Object()));
        assertEquals(HttpStatus.OK.value(), resp.getStatus());
    }

    @Test
    void preHandle_tokenIsCaseSensitive() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("Secret", env("prod"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(it.preHandle(requestWith("secret"), resp, new Object()),
                "令牌比较是精确 equals，大小写不同即拒绝");
    }

    @Test
    void preHandle_failOpen_ignoresAnyHeader() throws Exception {
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("dev"));
        assertTrue(it.preHandle(requestWith("whatever"), new MockHttpServletResponse(), new Object()));
    }

    // ==================== 启动链路验收：不是"构造器抛异常"，而是"上下文起不来" ====================

    @Test
    void springContext_noToken_prodProfile_contextFailsToStart() {
        // 与网关侧对称：prod profile 缺令牌 → Spring 上下文起不来（进程退出）
        new ApplicationContextRunner()
                .withUserConfiguration(InternalTokenConfig.class)
                .withPropertyValues("turbofeed.internal.token=")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure, "prod profile 缺令牌时 Spring 上下文必须启动失败");
                    assertTrue(containsCause(failure, IllegalStateException.class),
                            "启动失败根因应为 IllegalStateException，实际：" + failure);
                });
    }

    @Test
    void springContext_noToken_devProfile_startsNormally() {
        new ApplicationContextRunner()
                .withUserConfiguration(InternalTokenConfig.class)
                .withPropertyValues("turbofeed.internal.token=")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "dev profile 缺令牌时上下文仍应正常启动（克隆即跑）");
                    assertTrue(ctx.containsBean("internalTokenInterceptor"));
                });
    }

    private static boolean containsCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** 只装配拦截器本身的最小配置，用于验证 Spring 启动期的 fail-fast 链路。 */
    @Configuration
    static class InternalTokenConfig {
        @Bean
        InternalTokenInterceptor internalTokenInterceptor(@Value("${turbofeed.internal.token:}") String token,
                                                         Environment env) {
            return new InternalTokenInterceptor(token, env);
        }
    }

    @Test
    void failOpenProfiles_containsExactlyDevAndLocal() {
        assertTrue(env("dev").acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES));
        assertTrue(env("local").acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES));
        assertFalse(env("test").acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES),
                "test 等其它 profile 不得进入 fail-open 豁免名单");
    }
}
