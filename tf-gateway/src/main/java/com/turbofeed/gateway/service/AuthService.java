package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.AuthProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.JwtUtil;
import com.turbofeed.shared.result.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 登录服务：演示账号校验 + JWT 签发。
 *
 * <p>演示阶段账号来自配置（turbofeed.auth.demo-users）；生产替换为数据库 + 哈希
 * （BCrypt/Argon2）校验并接入 refresh token，本类仅改校验实现，令牌签发契约不变。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties properties;
    private final JwtUtil jwtUtil;

    /**
     * 校验账号并签发 JWT。
     *
     * @return JWT，前端后续以 Authorization: Bearer &lt;token&gt; 携带
     * @throws BizException(UNAUTHORIZED) 用户名或密码错误
     */
    public String login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名与密码不能为空");
        }
        String expected = properties.getDemoUsers().get(username);
        // 恒定时间比较，避免时序侧信道泄露"账号是否存在"
        if (expected == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8))) {
            log.warn("登录失败: username={}", username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return jwtUtil.generateToken(username);
    }
}
