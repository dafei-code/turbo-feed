package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.AuthService;
import com.turbofeed.shared.result.Result;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口：登录换取 JWT。
 *
 * <p>登录成功后返回 JWT，前端存入 localStorage，后续请求在 Authorization 头携带
 * {@code Bearer <token>}。演示账号来自 init-local.sql 种子数据（13800138000 / 123456
 * 为管理员，13900139000 / 123456 为普通用户）；登录账号即手机号（凭证层），令牌 sub
 * 携带系统内部 uid（身份层，对标抖音账号体系）。生产改为查库 + 密码加盐哈希校验，
 * 并支持 refresh token。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录换取「访问令牌 + 刷新令牌」对。
     *
     * @param phone 登录手机号
     * @param password 密码
     * @return 统一返回结构，data 为令牌对（accessToken 短期 / refreshToken 长期）
     */
    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@RequestParam("phone") String phone,
                                                @RequestParam("password") String password) {
        return Result.ok(authService.login(phone, password));
    }

    /**
     * 用刷新令牌换发新「访问 + 刷新」对（刷新令牌轮换：旧 RT 吊销，新 RT 落库）。
     *
     * @param refreshToken 登录时签发的刷新令牌
     * @return 新令牌对
     */
    @PostMapping("/refresh")
    public Result<AuthService.LoginResult> refresh(@RequestParam("refreshToken") String refreshToken) {
        return Result.ok(authService.refresh(refreshToken));
    }

    /**
     * 登出：<b>全端失效</b>（吊销该用户全部访问令牌 + 刷新令牌）。
     *
     * <p><b>幂等</b>：未携带令牌（本就未登录）直接返回成功。从访问令牌解析 uid 后，
     * 由 {@code AuthService#logout} 统一走 {@link com.turbofeed.gateway.security.RefreshTokenStore#revokeAllForUser}
     * （RT key 删除 + 全部 jti 进黑名单），覆盖仍在有效期内的访问令牌，实现「一处登出全端即时失效」。
     * 访问令牌缺失 / 解析失败时退化为只吊销刷新令牌（单端）。全链路 fail-open：Redis 异常不阻断登出。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestParam(value = "refreshToken", required = false) String refreshToken) {
        String at = extractBearer(authorization);
        authService.logout(at, refreshToken);
        return Result.ok();
    }

    /** 从 Authorization 头提取 Bearer 令牌；缺失 / 前缀不符 / 为空返回 null（视为匿名）。 */
    private static String extractBearer(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
