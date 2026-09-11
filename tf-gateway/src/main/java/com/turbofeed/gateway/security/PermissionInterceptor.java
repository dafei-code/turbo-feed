package com.turbofeed.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限拦截器：对受保护接口按 {@link RequirePermission} 注解（或兜底默认权限）做角色鉴权。
 *
 * <p><b>为什么用拦截器而非 @PreAuthorize</b>：项目未引入 Spring Security，鉴权链由
 * {@link JwtAuthenticationFilter}（Servlet Filter）+ ThreadLocal {@link UserContextHolder}
 * 承载。拦截器在 DispatcherServlet 内运行，其抛出的 {@link
 * com.turbofeed.gateway.exception.BizException} 可被 {@link
 * com.turbofeed.gateway.exception.GlobalExceptionHandler}（@RestControllerAdvice）捕获，
 * 统一翻译为 {@code Result} 结构（40301 无权限），与业务接口错误体一致。</p>
 *
 * <p><b>执行时机</b>：{@link JwtAuthenticationFilter} 先于本拦截器把身份写入
 * {@link UserContextHolder}，故此处 {@link UserContextHolder#requirePermission(Permission...)}
 * 一定能取到身份。</p>
 *
 * <p><b>CORS 预检</b>：浏览器带自定义头（如 Authorization）的请求会先发 OPTIONS 预检，
 * 预检不带令牌，必须直接放行，否则预检被拒导致真实请求整体失败。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检（OPTIONS）不带令牌，由 Spring CORS 配置处理，此处直接放行。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 方法级 @RequirePermission 优先；缺省（/api/admin/** 兜底）要求内容审核权限。
        Permission[] required = null;
        if (handler instanceof HandlerMethod hm) {
            RequirePermission ann = hm.getMethodAnnotation(RequirePermission.class);
            if (ann != null) {
                required = ann.value();
            }
        }
        if (required == null || required.length == 0) {
            required = new Permission[]{ Permission.CONTENT_REVIEW };
        }
        // 匿名 -> UNAUTHORIZED；权限不足 -> FORBIDDEN；其余放行。
        // 异常由 GlobalExceptionHandler 翻译为 Result，不在此自行写响应。
        UserContextHolder.requirePermission(required);
        return true;
    }
}
