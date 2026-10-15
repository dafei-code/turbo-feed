package com.turbofeed.feedengine.config;

import com.turbofeed.feedengine.interceptor.InternalTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 引擎 Web 层配置：注册服务间内部接口鉴权拦截器。
 *
 * <p>仅对 {@code /internal/**} 生效（网关 → 引擎的服务间调用），不影响对外 API
 * （引擎不直接暴露 {@code /api/**}，统一由网关承接）与 actuator 端点。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final InternalTokenInterceptor internalTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 服务间内部接口令牌校验：仅覆盖 /internal/**（网关经 FeedEngineClient 调用），
        // 与网关侧守护 /internal/review/** 对称，闭合「内部接口无鉴权」这一评审 P0-2 缺口。
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/internal/**");
    }
}
