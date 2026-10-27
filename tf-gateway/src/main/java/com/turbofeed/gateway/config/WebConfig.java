package com.turbofeed.gateway.config;

import com.turbofeed.gateway.security.InternalTokenInterceptor;
import com.turbofeed.gateway.security.PermissionInterceptor;
import com.turbofeed.gateway.web.ApiVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Web 层配置：跨域放行 + 已上传媒体的静态资源映射。
 *
 * <p><b>跨域（CORS）</b>：前端是独立静态页（{@code file://} 或以其它端口起的服务），
 * 与后端 {@code :8080} 不同源，浏览器的同源策略会拦截所有 {@code /api/**} 请求——
 * 没有这段配置，登录与上传在浏览器里必然失败（且失败表现为"看不到响应"的
 * CORS 报错，极具迷惑性）。此处对 {@code /api/**} 放行，鉴权不依赖 Cookie 而走
 * {@code Authorization: Bearer <JWT>} 请求头，故无需开启 {@code allowCredentials}
 * （开启后 {@code allowedOriginPatterns("*")} 会被 Spring 拒绝，二者互斥）。</p>
 *
 * <p><b>静态资源映射</b>：把 {@code /media/**} 映射到本地存储目录，使
 * {@link LocalDiskStorageClient} 落盘的图片能被浏览器直接加载。
 * 目录取自 {@code turbofeed.media.local-dir} 并与存储实现共享同一配置项，
 * 避免"写入目录"与"读取目录"两处配置漂移导致 404。</p>
 *
 * <p><b>演进</b>：改用对象存储后，本类的静态映射随之退役——URL 直接指向
 * MinIO/COS 的公网地址或 CDN 域名，不再经过本进程。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /** 已上传媒体的 URL 前缀，与 {@link LocalDiskStorageClient} 的 mediaId 前缀保持一致。 */
    private static final String MEDIA_URL_PREFIX = "/media/**";

    private final MediaProperties mediaProperties;
    private final PermissionInterceptor permissionInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 只注册 /api/**：Ant 的 ** 跨多段，因此同时覆盖 /api/v1/** —— 版本前缀不需要另加一条。
        // （若有人把它收窄成 /api/*，版本化路径会立刻在浏览器里 CORS 失败，且报错与"服务没起"极像。）
        registry.addMapping("/api/**")
                // 用 patterns 而非 origins：静态页以 file:// 打开时 Origin 为 "null"，
                // 精确 origin 列表匹配不到，通配符模式可覆盖该场景
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // RBAC 权限拦截：对 /api/admin/** 按 @RequirePermission 注解做角色鉴权；
        // 未声明注解的端点一律拒绝（deny-by-default），审核员(REVIEWER)与系统管理员(ADMIN)仅能进已声明权限的端点，普通用户(USER)一律 40301。
        // 路径匹配走 Ant 风格，仅覆盖 /api/admin/**，不影响普通业务接口（/api/media/** 仅要求登录态）。
        // 注意：不再注册 AdminAuthInterceptor（其仅放行 ADMIN，会挡住审核员），鉴权统一收口到 PermissionInterceptor。
        //
        // ⚠ 必须同时注册新旧两种前缀（P2-3）：Ant 模式 /api/admin/** 匹配不到 /api/v1/admin/**。
        // 请求在到达 DispatcherServlet 前已被 LegacyApiPathFilter 重写成 /api/v1/admin/**，
        // 只留老模式 = 后台接口静默失去鉴权（且没有任何测试会红）。老模式保留作为纵深防御：
        // 万一过滤器被摘掉，RBAC 仍在。
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns(ApiVersion.LEGACY + "/admin/**", ApiVersion.V1 + "/admin/**");
        // 服务间内部接口令牌校验（评审 doc P0-2）：守护 /internal/**（当前 /internal/review，
        // 由 tf-feed-engine 经 GatewayReviewClient 回调）。未配置 turbofeed.internal.token 时 fail-open。
        // 内部接口不参与版本演进（见 ApiVersion#INTERNAL），因此没有 /internal/v1/** 这种形态。
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/internal/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(mediaProperties.getLocalDir())
                .toAbsolutePath()
                .normalize()
                .toString();
        // addResourceLocations 要求目录以 "/" 结尾，否则最后一级会被当作文件名前缀
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(MEDIA_URL_PREFIX)
                .addResourceLocations("file:" + location);
    }
}
