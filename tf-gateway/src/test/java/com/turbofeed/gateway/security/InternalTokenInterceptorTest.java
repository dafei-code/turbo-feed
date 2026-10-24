package com.turbofeed.gateway.security;

import com.turbofeed.gateway.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
 * {@link InternalTokenInterceptor} 单测（M0 / R1：内部令牌 fail-fast）。
 *
 * <p><b>守护的契约</b>：内部令牌缺失时，<b>只有 dev / local 允许 fail-open</b>，
 * 其余 profile（含无 profile 的 default、prod）必须启动即失败 —— 不能靠运行期 warn 蒙混过关。</p>
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
        // 无 profile（default）不在豁免名单：必须启动失败
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
        // 中间环境同样不放过：漏配不能静默
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
    void construct_noToken_devProfile_allowedAndFailOpen() {
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("dev"));
        assertTrue(it.preHandle(requestWith(null), new MockHttpServletResponse(), new Object()),
                "dev profile 缺令牌时应 fail-open 放行（克隆即跑）");
    }

    @Test
    void construct_noToken_localProfile_allowedAndFailOpen() {
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("local"));
        assertTrue(it.preHandle(requestWith(null), new MockHttpServletResponse(), new Object()),
                "local profile 缺令牌时应 fail-open 放行");
    }

    // ==================== 配置令牌后的校验行为 ====================

    @Test
    void preHandle_configuredToken_missingHeader_rejects() {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        BizException ex = assertThrows(BizException.class,
                () -> it.preHandle(requestWith(null), new MockHttpServletResponse(), new Object()));
        assertEquals("内部接口需要有效的 X-Internal-Token", ex.getMessage());
    }

    @Test
    void preHandle_configuredToken_wrongHeader_rejects() {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        assertThrows(BizException.class,
                () -> it.preHandle(requestWith("wrong"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void preHandle_configuredToken_correctHeader_passes() {
        InternalTokenInterceptor it = new InternalTokenInterceptor("secret", env("prod"));
        assertTrue(it.preHandle(requestWith("secret"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void preHandle_tokenIsCaseSensitive() {
        // 令牌比较是精确 equals：大小写不同即拒绝，避免"看起来对"的弱校验
        InternalTokenInterceptor it = new InternalTokenInterceptor("Secret", env("prod"));
        assertThrows(BizException.class,
                () -> it.preHandle(requestWith("secret"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void preHandle_failOpen_ignoresAnyHeader() {
        // fail-open 下即便带了错误头也放行（因为根本没有期望值可比）
        InternalTokenInterceptor it = new InternalTokenInterceptor("", env("dev"));
        assertTrue(it.preHandle(requestWith("whatever"), new MockHttpServletResponse(), new Object()));
    }

    // ==================== 启动链路验收：不是"构造器抛异常"，而是"上下文起不来" ====================

    @Test
    void springContext_noToken_prodProfile_contextFailsToStart() {
        // 文档验收口径：「不设令牌起 prod profile 应进程退出」。
        // 构造器抛异常只是手段，这里验证的是 Spring 真的把它变成启动失败（BeanCreationException → 上下文起不来）。
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
        // 反向守护：dev profile 必须仍然能起来，否则「克隆即跑」被打破
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
        // 豁免名单必须保持最小：新增 profile 需要显式评审，不能被悄悄加进来
        MockEnvironment dev = env("dev");
        MockEnvironment local = env("local");
        MockEnvironment test = env("test");
        assertTrue(dev.acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES));
        assertTrue(local.acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES));
        assertFalse(test.acceptsProfiles(InternalTokenInterceptor.FAIL_OPEN_PROFILES),
                "test 等其它 profile 不得进入 fail-open 豁免名单");
    }
}
