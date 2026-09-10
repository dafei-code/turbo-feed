package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.AdminProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.UserJdbcRepository;
import com.turbofeed.gateway.security.JwtUtil;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 登录服务：查 user 表 + BCrypt 校验，通过后签发 JWT。
 *
 * <p>登录账号即手机号（凭证层，仅用于登录入口定位 UID）；校验通过后签发 JWT，
 * 令牌 sub 携带系统内部 {@code uid}（身份层 / 分片键），下游 {@code UserContextHolder}
 * 透传 uid，业务层按 uid 精准命中分片。密码落库为 BCrypt 哈希，此处用
 * {@link BCryptPasswordEncoder#matches} 恒定时间比对。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserJdbcRepository userJdbcRepository;
    private final JwtUtil jwtUtil;
    private final AdminProperties adminProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 校验手机号 + 密码并签发 JWT。
     *
     * @return JWT（sub 为 uid，非手机号），前端后续以 Authorization: Bearer &lt;token&gt; 携带
     * @throws BizException 手机号或密码错误 / 账号禁用
     */
    public String login(String phone, String password) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号与密码不能为空");
        }
        Optional<UserJdbcRepository.UserIdHash> account = userJdbcRepository.findByPhone(phone);
        // 账号不存在：明确提示「未注册」（演示 UX 优先）。
        // 代价：开放手机号是否注册的枚举，放弃此前「账号不存在 / 密码错误」统一提示的防时序侧信道设计；
        // 生产建议恢复统一「手机号或密码错误」。
        if (account.isEmpty()) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_REGISTERED, "账号未注册，请先注册");
        }
        // 密码不匹配：返回「手机号或密码错误」（不额外暴露账号存在性之外的信息）。
        if (!passwordEncoder.matches(password, account.get().passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }
        if (account.get().status() == 2) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        // 令牌内携带 UID 而非手机号（对标抖音账号体系）；
        // demo 阶段角色由手机号是否命中管理员白名单派生（生产改 user 表 role 列）。
        String role = adminProperties.getPhones().contains(phone) ? "ADMIN" : "USER";
        return jwtUtil.generateToken(String.valueOf(account.get().id()), role);
    }
}
