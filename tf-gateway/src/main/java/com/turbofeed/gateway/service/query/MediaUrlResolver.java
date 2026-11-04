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
}
