package com.turbofeed.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员接口鉴权拦截器：对所有 {@code /api/admin/**} 强制执行管理员角色校验。
 *
 * <p><b>为什么用拦截器而非 @PreAuthorize</b>：项目未引入 Spring Security，
 * 鉴权链由 {@link JwtAuthenticationFilter}（Servlet Filter）+ ThreadLocal
 * {@link UserContextHolder} 承载。拦截器在 DispatcherServlet 内运行，其抛出的
 * {@link com.turbofeed.gateway.exception.BizException} 可被 {@link
 * com.turbofeed.gateway.exception.GlobalExceptionHandler}（@RestControllerAdvice）捕获，
 * 统一翻译为 {@code Result} 结构（40301 无权限），与业务接口错误体一致。</p>
 *
 * <p><b>执行时机</b>：{@link JwtAuthenticationFilter} 先于本拦截器把身份写入
 * {@link UserContextHolder}，故此处 {@link UserContextHolder#requireAdmin()} 一定能取到身份。</p>
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检（OPTIONS）不带令牌，由 Spring CORS 配置处理，此处直接放行。
        // 否则预检被 requireAdmin 拒绝会导致浏览器不发真实请求（审核页拉不到数据）。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 匿名 -> UNAUTHORIZED；已登录非管理员 -> FORBIDDEN；其余放行。
        // 异常由 GlobalExceptionHandler 翻译为 Result，不在此自行写响应。
        UserContextHolder.requireAdmin();
        return true;
    }
}
