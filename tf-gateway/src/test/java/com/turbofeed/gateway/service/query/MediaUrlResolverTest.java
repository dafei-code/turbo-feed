package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-0015 推荐流读取侧 403 修复：从已物化的展示 URL 反推 mediaId 并重新签名。
 *
 * <p>纯 Mockito，不启 Spring / 不连 MinIO：验证对象名抽取、minio 重签、非 minio 与
 * 无法识别路径的原样回退（避免把历史公网直链改坏）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaUrlResolverTest {

    @Mock
    private MediaStorageClient storageClient;

    private static MediaProperties minioProps() {
        MediaProperties p = new MediaProperties();
        p.setStorage("minio");
        p.setPublicUrlBase("https://oss.turbofeed.com/");
        p.getMinio().setUrlExpirySeconds(900);
        return p;
    }

    @Test
    void extractMediaId_minioPresignedUrl_stripsQueryAndBucket() {
        assertEquals("media/42/uuid.jpg", MediaUrlResolver.extractMediaId(
                "http://127.0.0.1:9000/turbofeed-media/media/42/uuid.jpg"
                        + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=900"));
    }

    @Test
    void extractMediaId_publicBaseUrl_andBucketNamedMedia() {
        assertEquals("media/7/a.png",
                MediaUrlResolver.extractMediaId("https://oss.turbofeed.com/media/7/a.png"));
        // bucket 恰好叫 media：lastIndexOf("/media/") 必须跳过 bucket 段
        assertEquals("media/7/a.png",
                MediaUrlResolver.extractMediaId("http://host:9000/media/media/7/a.png?sig=1"));
    }

    @Test
    void extractMediaId_rejectsNonObjectPaths() {
        assertNull(MediaUrlResolver.extractMediaId("https://cdn.example.com/banner.png"));
        assertNull(MediaUrlResolver.extractMediaId("https://cdn.example.com/media/not-a-user/x.png"));
        assertNull(MediaUrlResolver.extractMediaId("https://cdn.example.com/multimedia/42/x.png"));
        assertNull(MediaUrlResolver.extractMediaId(""));
        assertNull(MediaUrlResolver.extractMediaId(null));
    }

    @Test
    void resolveFromUrl_minio_resignsByObjectName() {
        when(storageClient.presignedGetUrl(eq("media/42/uuid.jpg"), eq(Duration.ofSeconds(900))))
                .thenReturn("http://fresh/media/42/uuid.jpg?sig=new");
        MediaUrlResolver resolver = new MediaUrlResolver(minioProps(), storageClient);

        String out = resolver.resolveFromUrl(
                "http://127.0.0.1:9000/bucket/media/42/uuid.jpg?sig=old");

        assertEquals("http://fresh/media/42/uuid.jpg?sig=new", out);
        verify(storageClient).presignedGetUrl("media/42/uuid.jpg", Duration.ofSeconds(900));
    }

    @Test
    void resolveFromUrl_nonMinio_keepsOriginal() {
        MediaProperties p = minioProps();
        p.setStorage("local");
        MediaUrlResolver resolver = new MediaUrlResolver(p, storageClient);

        assertEquals("http://legacy/x.png", resolver.resolveFromUrl("http://legacy/x.png"));
        verifyNoInteractions(storageClient);
    }

    @Test
    void resolveFromUrl_unknownPathOrBlank_keepsOriginal() {
        MediaUrlResolver resolver = new MediaUrlResolver(minioProps(), storageClient);

        assertEquals("https://cdn.example.com/banner.png",
                resolver.resolveFromUrl("https://cdn.example.com/banner.png"));
        assertNull(resolver.resolveFromUrl(null));
        assertEquals("", resolver.resolveFromUrl(""));
        verifyNoInteractions(storageClient);
    }
}
