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

    /** 清理上下文（请求结束兜底，防止线程池串号）。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
