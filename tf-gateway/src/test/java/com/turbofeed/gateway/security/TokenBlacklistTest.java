package com.turbofeed.gateway.security;

import com.turbofeed.gateway.config.SecurityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TokenBlacklist} 单测（M0 / R10：失效模式显式化）。
 *
 * <p><b>守护的契约</b>：Redis 出错时到底是放行还是拒绝，必须由
 * {@code turbofeed.security.token-blacklist-fail-open-on-error} 显式决定，
 * 且两种模式都要留下指标痕迹 —— 不允许"隐式二选一"。</p>
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistTest {

    private static final String METRIC = "turbofeed.security.blacklist.error";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private JwtUtil jwtUtil;

    private SecurityProperties props;
    private SimpleMeterRegistry registry;
    private TokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        props = new SecurityProperties();
        registry = new SimpleMeterRegistry();
        blacklist = new TokenBlacklist(redisTemplate, jwtUtil, props, registry);
    }

    private double errorCount(String mode) {
        var counter = registry.find(METRIC).tag("mode", mode).counter();
        return counter == null ? 0d : counter.count();
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    // ==================== 查询：正常路径 ====================

    @Test
    void isRevoked_keyExists_returnsTrue() {
        when(redisTemplate.hasKey("turbofeed:jwt:blacklist:j1")).thenReturn(true);
        assertTrue(blacklist.isRevoked("j1"));
    }

    @Test
    void isRevoked_keyAbsent_returnsFalse() {
        when(redisTemplate.hasKey("turbofeed:jwt:blacklist:j1")).thenReturn(false);
        assertFalse(blacklist.isRevoked("j1"));
    }

    @Test
    void isRevoked_blankJti_skipsRedis() {
        assertFalse(blacklist.isRevoked(null));
        assertFalse(blacklist.isRevoked(""));
        verifyNoInteractions(redisTemplate);
    }

    // ==================== 查询：Redis 故障的两种显式模式 ====================

    @Test
    void isRevoked_redisError_defaultFailClosed_rejects() {
        // 默认 false = fail-closed：无法证明未吊销 → 视为已吊销
        assertFalse(props.isTokenBlacklistFailOpenOnError(), "默认必须是 fail-closed");
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(blacklist.isRevoked("j1"), "fail-closed 下 Redis 故障必须拒绝请求");
        assertEquals(1.0, errorCount("closed"));
        assertEquals(0.0, errorCount("open"), "fail-closed 不得计入 fail-open 指标");
    }

    @Test
    void isRevoked_redisError_failOpenSwitch_allows() {
        props.setTokenBlacklistFailOpenOnError(true);
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(blacklist.isRevoked("j1"), "显式 fail-open 下放行");
        assertEquals(1.0, errorCount("open"), "fail-open 必须留下指标痕迹，不能静默放行");
        assertEquals(0.0, errorCount("closed"));
    }

    @Test
    void isRevoked_repeatedErrors_countedEachTime() {
        // 日志有节流，但指标不能节流：否则监控看不到故障持续
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        for (int i = 0; i < 5; i++) {
            blacklist.isRevoked("j1");
        }
        assertEquals(5.0, errorCount("closed"));
    }

    // ==================== 写入：吊销 ====================

    @Test
    void revokeToken_writesWithRemainingTtl() {
        long exp = nowSec() + 3600;
        when(jwtUtil.parseForRevoke("tok")).thenReturn(new JwtUtil.RevocableToken("j1", exp));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        blacklist.revokeToken("tok");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("turbofeed:jwt:blacklist:j1"), eq(""), ttl.capture());
        long sec = ttl.getValue().getSeconds();
        assertTrue(sec >= 3595 && sec <= 3600, "TTL 应为令牌剩余有效期，实际 " + sec + "s");
    }

    @Test
    void revokeToken_alreadyExpired_skipsWrite() {
        when(jwtUtil.parseForRevoke("tok")).thenReturn(new JwtUtil.RevocableToken("j1", nowSec() - 10));
        blacklist.revokeToken("tok");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revokeToken_unparsableToken_skipsWriteAndDoesNotThrow() {
        // 过期/被篡改令牌：本就不可用，登出不应报错
        when(jwtUtil.parseForRevoke("bad")).thenThrow(new IllegalArgumentException("bad token"));
        assertDoesNotThrow(() -> blacklist.revokeToken("bad"));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revokeToken_redisWriteFailure_doesNotThrowButIsCounted() {
        long exp = nowSec() + 3600;
        when(jwtUtil.parseForRevoke("tok")).thenReturn(new JwtUtil.RevocableToken("j1", exp));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // set 是 void 方法，只能用 doThrow(...).when(...) 打桩
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> blacklist.revokeToken("tok"), "写入失败不应让登出接口抛异常");
        assertEquals(1.0, errorCount("write"), "吊销未生效必须留下指标，不能静默吞掉");
    }

    // ==================== 写入：按 jti 批量吊销 ====================

    @Test
    void revokeJti_writesWithGivenTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        blacklist.revokeJti("j9", 120);
        verify(valueOps).set("turbofeed:jwt:blacklist:j9", "", Duration.ofSeconds(120));
    }

    @Test
    void revokeJti_blankJti_skips() {
        blacklist.revokeJti(null, 120);
        blacklist.revokeJti("", 120);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void revokeJti_redisFailure_doesNotThrowButIsCounted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // set 是 void 方法，只能用 doThrow(...).when(...) 打桩
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));
        assertDoesNotThrow(() -> blacklist.revokeJti("j9", 120));
        assertEquals(1.0, errorCount("write"));
    }
}
