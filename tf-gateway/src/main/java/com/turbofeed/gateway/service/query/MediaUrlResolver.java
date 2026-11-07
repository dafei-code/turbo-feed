package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 展示侧媒体 URL 解析：把「对象名（mediaId）」解析成浏览器可访问的地址。
 *
 * <p><b>为什么必须有它</b>：MinIO 桶为私有（{@code publicRead=false}，安全默认），
 * 而 DB 的 {@code url} 列落的是「上传时刻生成的预签名 URL」，默认 900 秒后过期 → 浏览器 403。
 * 本解析器在<b>读取展示时</b>按 mediaId 重新签名（minio 分支），或拼公网基址（非 minio 分支），
 * 保证每次返回给前端的都是<b>新鲜、未过期</b>的地址。</p>
 *
 * <p><b>绝不持久化预签名 URL</b>：预签名是短时凭据，落库/发事件时只传 mediaId 或原样，
 * 由读取侧（本类）统一现签——避免「库里存着已过期签名」导致全站图片 403。</p>
 *
 * <p><b>性能</b>：minio 分支的 {@code getPresignedObjectUrl} 纯本地 HMAC 签名、无网络往返，
 * 一页 N 帖 × 9 图也仅是几百次本地运算，开销可忽略。</p>
 */
@Component
public class MediaUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(MediaUrlResolver.class);

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;

    public MediaUrlResolver(MediaProperties properties, MediaStorageClient storageClient) {
        this.properties = properties;
        this.storageClient = storageClient;
    }

    /**
     * 由 mediaId（对象名 {@code media/{userId}/{uuid}.{ext}}）解析展示 URL。
     *
     * @param mediaId 对象名；为 null/空时返回 {@code null}（调用方按无图处理）
     */
    public String resolve(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return null;
        }
        if ("minio".equals(properties.getStorage())) {
            try {
                return storageClient.presignedGetUrl(mediaId,
                        Duration.ofSeconds(properties.getMinio().getUrlExpirySeconds()));
            } catch (Exception e) {
                // 签名异常兜底：回退公网基址，宁可暴露不可达地址也不抛，避免整页 500
                log.warn("预签名读取 URL 生成失败（回退公网基址）: mediaId={}, {}", mediaId, e.getMessage());
            }
        }
        return properties.getPublicUrlBase() + mediaId;
    }

    /**
     * 从「已物化的展示 URL」反推 mediaId 并重新签名（ADR-0015 推荐流读取侧 403 修复）。
     *
     * <p><b>为什么需要它</b>：引擎时间线里物化的 {@code FeedItemView.imageUrls()} 是<b>投递时刻</b>
     * 现签的 URL 列表（契约里只有 URL，没有逐图 mediaId）。私有桶下这些地址 900 秒后过期，
     * 网关把引擎下发的条目直接映射给前端就会全页 403。读取时按 URL 里的对象名重签，
     * 才能做到「每次请求都是新鲜地址」，且与 {@link #resolve(String)} 同一套签名口径。</p>
     *
     * <p><b>不改契约</b>：给 {@code FeedItemView} 增加 mediaIds 字段会牵动引擎与历史 Redis 数据；
     * 反推是零迁移方案。反推失败（外部 CDN、自定义路径、编码异常）时<b>原样返回</b>，
     * 保证历史直链不被改坏。非 minio 存储没有签名过期问题，直接原样返回。</p>
     *
     * @param staleUrl 引擎下发/DB 里可能已过期的展示 URL
     * @return 新鲜可访问地址；无法反推时返回入参原值
     */
    public String resolveFromUrl(String staleUrl) {
        if (staleUrl == null || staleUrl.isBlank()) {
            return staleUrl;
        }
        if (!"minio".equals(properties.getStorage())) {
            return staleUrl;
        }
        String mediaId = extractMediaId(staleUrl);
        if (mediaId == null) {
            return staleUrl;
        }
        return resolve(mediaId);
    }

    /**
     * 从展示 URL 中抽取对象名 {@code media/{userId}/{uuid}.{ext}}。
     *
     * <p>兼容两种形态：MinIO 预签名 {@code http://host:9000/{bucket}/media/42/x.jpg?X-Amz-...}，
     * 与公网基址 {@code https://oss.turbofeed.com/media/42/x.jpg}。先去 query、再去 scheme+host，
     * 用 {@code lastIndexOf("/media/")} 跳过 bucket 段（bucket 名本身含 media 也不会取错）；
     * 最后校验第二段是数字 userId，避免把路径里恰好含 media 的 CDN 资源误当对象名。</p>
     *
     * @return 对象名；无法识别时返回 {@code null}
     */
    static String extractMediaId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int scheme = path.indexOf("://");
        String afterHost = scheme >= 0 ? path.substring(scheme + 3) : path;
        int slash = afterHost.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String padded = "/" + afterHost.substring(slash + 1);
        int idx = padded.lastIndexOf("/media/");
        if (idx < 0) {
            return null;
        }
        String candidate = padded.substring(idx + 1);
        String[] parts = candidate.split("/");
        if (parts.length < 3 || !"media".equals(parts[0])) {
            return null;
        }
        try {
            Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return candidate;
    }
}
