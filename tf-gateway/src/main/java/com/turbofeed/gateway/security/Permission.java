package com.turbofeed.gateway.security;

/**
 * 权限枚举（资源:操作）——RBAC 的原子授权单元。
 *
 * <p>角色绑定权限集合，接口按权限校验而非按角色名硬编码，这样新增角色/权限只需
 * 加枚举值 + 一行映射，业务接口用 {@link RequirePermission} 声明所需权限，零侵入扩展。
 * 当前权限集合覆盖抖音式审核后台的演示范围，生产可按模块继续细分。</p>
 */
public enum Permission {

    /** 内容审核：通过/驳回、举报复核、申诉复核 */
    CONTENT_REVIEW,

    /** 高危内容下架处置 */
    CONTENT_TAKEDOWN,

    /** 数据看板（只读） */
    DASHBOARD_VIEW,

    /** 用户管理 */
    USER_MANAGE,

    /** 系统配置 */
    SYSTEM_CONFIG,

    /** 信用分管理 */
    CREDIT_MANAGE
}
