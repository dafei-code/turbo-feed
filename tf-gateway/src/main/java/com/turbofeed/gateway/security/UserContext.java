package com.turbofeed.gateway.security;

import java.util.Set;

/**
 * 当前请求的认证身份（不可变值对象）。
 *
 * <p>由 {@link JwtAuthenticationFilter} 在请求入口验签 JWT 后创建，经
 * {@link UserContextHolder} 绑定到当前线程。业务层只读，不承载派发/回写职责。</p>
 *
 * @param userId 用户 ID（来自 JWT sub 声明，服务端解析，客户端不可指定）
 * @param role   角色（来自 JWT role 声明，demo 由手机号白名单派生；生产改 user 表 role 列）
 */
public record UserContext(String userId, Role role) {

    /** 静态工厂：显式校验非空，杜绝无身份上下文被绑定（默认 USER 角色）。 */
    public static UserContext of(String userId) {
        return of(userId, Role.USER);
    }

    /** 静态工厂：携带角色，null 角色降级为 USER（最小权限）。 */
    public static UserContext of(String userId, Role role) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return new UserContext(userId, role == null ? Role.USER : role);
    }

    /** 是否管理员（供鉴权快捷判断）。 */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** 该身份拥有的全部权限（来自角色映射，不可变）。 */
    public Set<Permission> permissions() {
        return role.permissions();
    }

    /** 是否拥有指定权限（RBAC 原子校验入口）。 */
    public boolean hasPermission(Permission permission) {
        return role.hasPermission(permission);
    }
}
