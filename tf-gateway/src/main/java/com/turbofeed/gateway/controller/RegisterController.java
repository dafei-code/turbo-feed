package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.UserService;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册接口：创建真实用户（本地密码哈希，不接第三方）。
 *
 * <p>与 {@code AuthController} 同属 {@code /api/auth} 命名空间；注册成功后前端跳登录页
 * 换取 JWT，再凭令牌走上传链路。手机号全局唯一，重复注册由 Service 层拒绝。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class RegisterController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<?> register(@RequestParam("phone") String phone,
                              @RequestParam("password") String password,
                              @RequestParam(value = "nickname", required = false, defaultValue = "") String nickname) {
        return Result.ok(userService.register(phone, password, nickname));
    }
}
