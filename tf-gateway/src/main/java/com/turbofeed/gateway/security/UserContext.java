package com.turbofeed.gateway.security;

/**
 * 当前请求的认证身份（不可变值对象）。
 *
 * <p>由 {@link JwtAuthenticationFilter} 在请求入口验签 JWT 后创建，经
 * {@link UserContextHolder} 绑定到当前线程。业务层只读，不承载派发/回写职责。</p>
 *
 * @param userId 用户 ID（来自 JWT sub 声明，服务端解析，客户端不可指定）
 */
public record UserContext(String userId) {

    /** 静态工厂：显式校验非空，杜绝无身份上下文被绑定。 */
    public static UserContext of(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return new UserContext(userId);
    }
}
