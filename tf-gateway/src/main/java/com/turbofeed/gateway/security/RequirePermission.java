package com.turbofeed.gateway.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明控制器方法所需的权限集合。
 *
 * <p>{@link PermissionInterceptor} 在 preHandle 阶段读取本注解并调用
 * {@link UserContextHolder#requirePermission(Permission...)} 校验；缺失注解时
 * （如 {@code /api/admin/**} 未声明权限）一律拒绝（deny-by-default），
 * 强制每个管理端点显式声明所需权限，杜绝漏标注解导致的越权面。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 访问该方法必须具备的权限（全部满足）。 */
    Permission[] value();
}
