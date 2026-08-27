package com.turbofeed.gateway.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证过滤器：请求入口统一验签 {@code Authorization: Bearer <JWT>}，
 * 将身份绑定到 {@link UserContextHolder}（ThreadLocal），请求结束 finally 清理。
 *
 * <p>身份判定规则：</p>
 * <ul>
 *   <li><b>未携带令牌</b>——放行为匿名请求。是否要求登录由业务调用点
 *       {@code UserContextHolder.requireUserId()} 决定（公开接口合法匿名，
 *       受保护接口显式失败返回 UNAUTHORIZED），避免过滤器层一刀切拦截公开端点；</li>
 *   <li><b>携带令牌但验签失败 / 过期 / 格式非法</b>——立即抛
 *       {@link com.turbofeed.gateway.exception.BizException}(UNAUTHORIZED)。
 *       持有无效凭证却不拒绝、静默按匿名放行，会掩盖令牌异常，属安全反模式。</li>
 * </ul>
 *
 * <p><b>串号防护</b>：Servlet 容器线程池复用线程，ThreadLocal 残留会导致上一个请求的
 * 身份泄漏给下一个请求。本过滤器在 finally 中无条件 {@code clear()}，
 * 即使业务抛异常也保证清理，这是 ThreadLocal 身份模式的安全底线。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
            if (token != null) {
                // 验签失败/过期直接抛 UNAUTHORIZED，不静默匿名（见类注释）
                UserContextHolder.set(UserContext.of(jwtUtil.parseUserId(token)));
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    /** 提取 Bearer 令牌；头缺失 / 前缀不符 / 令牌为空返回 null（视为匿名）。 */
    private static String extractBearerToken(String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
