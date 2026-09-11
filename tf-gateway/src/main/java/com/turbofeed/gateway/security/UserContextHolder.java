package com.turbofeed.gateway.security;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;

/**
 * 请求级身份上下文（ThreadLocal 绑定，参考 Spring Security SecurityContextHolder 模式）。
 *
 * <p><b>生命周期契约</b>：由 {@link JwtAuthenticationFilter} 在请求进入时
 * {@link #set(UserContext)}，请求结束时（finally）{@link #clear()}——由 Servlet
 * 容器线程池复用线程，漏清理会导致<b>跨用户身份串号</b>（严重安全事故），
 * 因此本类不做静默降级，缺失身份的业务调用一律显式失败。</p>
 *
 * <p><b>已知边界</b>：ThreadLocal 不跨线程传递。业务需要异步处理时，必须在提交任务前
 * 显式快照身份（{@code UserContext ctx = UserContextHolder.get()}）并作为参数传入，
 * 不得在子线程内直接取用。</p>
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
        // 工具类禁止实例化
    }

    /** 绑定身份（仅 {@link JwtAuthenticationFilter} 调用）。 */
    public static void set(UserContext context) {
        CONTEXT.set(context);
    }

    /** 当前身份；匿名请求返回 {@code null}（不抛异常，公开接口合法匿名）。 */
    public static UserContext get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前用户 ID；匿名（未携带有效令牌）时抛 {@link BizException}(UNAUTHORIZED)。
     *
     * <p>供需要登录态的业务方法使用，取代方法签名上的身份参数——
     * 令牌缺失的失败语义由调用点显式声明，而非依赖参数注入。</p>
     */
    public static String requireUserId() {
        UserContext context = CONTEXT.get();
        if (context == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return context.userId();
    }

    /**
     * 要求当前操作为管理员；鉴权失败时抛 {@link BizException}：
     * <ul>
     *   <li>匿名（未登录）-> {@code UNAUTHORIZED}（请先登录）；</li>
     *   <li>已登录但非管理员 -> {@code FORBIDDEN}（无权限访问）。</li>
     * </ul>
     * 供 {@code /api/admin/**} 鉴权使用（{@link AdminAuthInterceptor} 在 preHandle 调用）。
     */
    public static void requireAdmin() {
        UserContext context = CONTEXT.get();
        if (context == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (!context.isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    /**
     * RBAC 原子权限校验：调用点声明所需 {@link Permission}（全部满足才放行）。
     * 鉴权失败时抛 {@link BizException}：
     * <ul>
     *   <li>匿名（未登录）-> {@code UNAUTHORIZED}（请先登录）；</li>
     *   <li>已登录但缺少任一所需权限 -> {@code FORBIDDEN}（无权限访问）。</li>
     * </ul>
     * 供 {@link PermissionInterceptor}（读取 {@link RequirePermission} 注解）在 preHandle 调用。
     * 新增角色只需在 {@link Role} 里加一行权限映射，业务接口用注解声明权限，此处无需改动。
     */
    public static void requirePermission(Permission... required) {
        UserContext context = CONTEXT.get();
        if (context == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (required == null || required.length == 0) {
            return; // 无权限要求：已登录即可
        }
        for (Permission permission : required) {
            if (!context.hasPermission(permission)) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权限访问：" + permission);
            }
        }
    }

    /** 清理上下文（请求结束兜底，防止线程池串号）。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
