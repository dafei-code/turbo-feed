package com.turbofeed.gateway.service;

import com.turbofeed.gateway.domain.User;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.UserJdbcRepository;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户注册服务：手机号格式校验 + 全局唯一去重 + BCrypt 哈希落库。
 *
 * <p>注册成功后返回脱敏的用户信息（不含 passwordHash）。登录流程在 {@link AuthService}，
 * 本服务不签发 JWT——保持「注册 / 登录」两个动作职责清晰，前端注册后跳登录页换取令牌。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserJdbcRepository userJdbcRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Map<String, Object> register(String phone, String password, String nickname) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号格式不正确");
        }
        if (password == null || password.length() < 6) {
            throw new BizException(ErrorCode.PARAM_ERROR, "密码至少 6 位");
        }
        if (userJdbcRepository.countByPhone(phone) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号已注册");
        }
        long id = snowflakeIdGenerator.nextId();
        String hash = passwordEncoder.encode(password);
        String name = (nickname == null || nickname.isBlank())
                ? "用户" + phone.substring(phone.length() - 4) : nickname;
        userJdbcRepository.insert(new User(id, phone, hash, name, 1));
        return Map.of(
                "id", id,
                "phone", phone,
                "nickname", name,
                "status", 1);
    }
}
