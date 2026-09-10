package com.turbofeed.gateway.security;

/**
 * 角色枚举（JWT 载荷携带，服务端解析）。
 *
 * <p>demo 阶段角色由登录时手机号是否命中管理员白名单派生（见 {@link AdminProperties}）；
 * 生产应改为 user 表 role 列 + RBAC。枚举仅承载「编码 <-> 运行时实例」映射，
 * 未知编码一律降级为 {@link #USER}（最小权限原则），不允许未知角色越权。</p>
 */
public enum Role {

    /** 普通用户（默认，最小权限） */
    USER("USER"),

    /** 管理员（可访问 /api/admin/**） */
    ADMIN("ADMIN");

    private final String code;

    Role(String code) {
        this.code = code;
    }

    /** 用于写入 JWT 声明的稳定编码。 */
    public String code() {
        return code;
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
