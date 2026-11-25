package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.share.ShareService;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 公开短链跳转 {@code GET /s/{code}}。
 *
 * <p><b>为什么不挂 {@code /api/v1}</b>：这是给人类点击的入口，不是业务 API；同时刻意
 * <b>不加 {@code @RequirePermission}</b>——{@code JwtAuthenticationFilter} 对无 token 请求匿名放行，
 * 所以公开访问不需要改安全过滤器、也不需要维护白名单（方案 §1.3）。</p>
 *
 * <p><b>必须回源校验内容状态</b>：内容下架/删除后，短链不能再跳到已删除内容（合规红线）。
 * 失败时返回 200 + 提示页而非 404，兼顾 SEO 与体验，与方案 §4.5 一致。</p>
 */
@RestController
@RequestMapping("/s")
public class ShortLinkController {

    private static final String UNAVAILABLE_PAGE = "<!DOCTYPE html><html lang=\"zh-CN\"><head>"
            + "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>内容已不可用</title><style>body{margin:0;min-height:100vh;display:flex;align-items:center;"
            + "justify-content:center;background:#0d1117;color:#e6edf3;font-family:system-ui,sans-serif}"
            + ".box{text-align:center;padding:32px}h1{font-size:20px;margin:0 0 12px}"
            + "p{color:#8b949e;font-size:14px;margin:0}a{color:#58a6ff;text-decoration:none}</style></head>"
            + "<body><div class=\"box\"><h1>该内容已不可用</h1>"
            + "<p>链接可能已失效，或内容已被作者删除 / 平台下架。</p>"
            + "<p style=\"margin-top:16px\"><a href=\"FEED_URL\">返回首页</a></p>"
            + "</div></body></html>";

    private final ShareService shareService;
    private final String frontendUrl;

    /**
     * 落地页默认用<b>站点相对路径</b> {@code /feed.html}：302 的 Location 允许相对 URI，
     * 浏览器按当前请求 host 解析，因此无论本地还是生产都不会误跳到写死的 localhost。
     * 前端若独立域名部署，须显式配置 {@code turbofeed.share.frontend-url} 为完整 URL
     * （prod compose 已把该变量暴露到环境变量，见 docker-compose.prod.yml）。
     */
    public ShortLinkController(ShareService shareService,
                               @Value("${" + FeatureFlagKeys.SHARE_FRONTEND_URL + ":/feed.html}") String frontendUrl) {
        this.shareService = shareService;
        this.frontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "/feed.html" : frontendUrl.trim();
    }

    /** 短链跳转：APPROVED → 302 到详情页并带归因参数；否则返回不可用提示页。 */
    @GetMapping("/{code}")
    public ResponseEntity<?> redirect(@PathVariable("code") String code) {
        ShareService.ResolveResult result = shareService.resolve(code);
        if (result == null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(UNAVAILABLE_PAGE.replace("FEED_URL", frontendUrl));
        }
        String location = frontendUrl + "?post=" + encode(result.postId())
                + "&from=share&trace=" + encode(code)
                + "&sharer=" + result.ownerId();
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
