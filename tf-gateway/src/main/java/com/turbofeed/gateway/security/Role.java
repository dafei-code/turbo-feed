package com.turbofeed.gateway.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * 角色枚举（JWT 载荷携带，服务端解析）。
 *
 * <p>角色由 user 表 role 列承载（真 RBAC），登录时 {@code AuthService} 直接读库派生 JWT，
 * 不在配置里维护任何白名单（工程规范：默认配置不写 demo / 敏感字面量）。每个角色绑定一组
 * {@link Permission}，接口按权限校验而非按角色名——新增角色只需在此加枚举值 +
 * 一行权限映射，业务代码零侵入。</p>
 *
 * <p>未知编码一律降级为 {@link #USER}（最小权限原则），不允许未知角色越权。</p>
 */
public enum Role {

    /** 普通用户（默认，最小权限，无后台权限） */
    USER("USER", Set.of()),

    /** 审核员：可审内容、高危下架、看板只读，无系统管理权限 */
    REVIEWER("REVIEWER", Set.of(
            Permission.CONTENT_REVIEW,
            Permission.CONTENT_TAKEDOWN,
            Permission.DASHBOARD_VIEW)),

    /** 系统管理员：拥有全部权限 */
    ADMIN("ADMIN", Set.copyOf(EnumSet.allOf(Permission.class)));

    private final String code;
    private final Set<Permission> permissions;

    Role(String code, Set<Permission> permissions) {
        this.code = code;
        this.permissions = permissions;
    }

    /** 用于写入 JWT 声明的稳定编码。 */
    public String code() {
        return code;
    }

    /** 该角色拥有的权限集合（不可变）。 */
    public Set<Permission> permissions() {
        return permissions;
    }

    /** 是否拥有指定权限。 */
    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    /** 从 JWT 声明编码解析角色；null / 未知一律降级 USER。 */
    public static Role from(String code) {
        if (code == null) {
            return USER;
        }
        for (Role role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        return USER;
    }
}
