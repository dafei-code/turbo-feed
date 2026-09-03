package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.AuthService;
import com.turbofeed.shared.result.Result;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口：登录换取 JWT。
 *
 * <p>登录成功后返回 JWT，前端存入 localStorage，后续请求在 Authorization 头携带
 * {@code Bearer <token>}。演示账号来自配置（turbofeed.auth.demo-users，键为手机号、
 * 值为密码与 uid）；登录账号即手机号（凭证层），令牌 sub 携带系统内部 uid（身份层，
 * 对标抖音账号体系）。生产改为查库 + 密码加盐哈希校验，并支持 refresh token。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录换取 JWT。
     *
     * @param phone 登录手机号
     * @param password 密码
     * @return 统一返回结构，data 为 JWT
     */
    @PostMapping("/login")
    public Result<String> login(@RequestParam("phone") String phone,
                                @RequestParam("password") String password) {
        return Result.ok(authService.login(phone, password));
    }
}
