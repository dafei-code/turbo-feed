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
 * <p>登录账号即手机号（对标抖音：手机号是凭证层，仅用于登录入口定位 UID）；校验通过后
 * 签发 JWT，令牌 sub 携带系统内部 {@code uid}（身份层 / 分片键），下游 UserContextHolder
 * 透传 uid，业务层按 uid 精准命中分片。生产改为查库（user_phone_router 做 phone→uid 映射）
 * + 哈希（BCrypt/Argon2）校验并接入 refresh token。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties properties;
    private final JwtUtil jwtUtil;

    /**
     * 校验手机号 + 密码并签发 JWT。
     *
     * @return JWT（sub 为 uid，非手机号），前端后续以 Authorization: Bearer &lt;token&gt; 携带
     * @throws BizException 手机号或密码错误
     */
    public String login(String phone, String password) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号与密码不能为空");
        }
        AuthProperties.DemoUser account = properties.getDemoUsers().get(phone);
        // 恒定时间比较，避免时序侧信道泄露"账号是否存在"
        if (account == null || !MessageDigest.isEqual(
                account.getPassword().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8))) {
            log.warn("登录失败: phone={}", phone);
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }
        // 对标抖音：令牌内携带 UID 而非手机号；手机号仅在登录入口定位 UID，不进入令牌、不参与分片
        return jwtUtil.generateToken(String.valueOf(account.getUid()));
    }
}
