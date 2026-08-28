package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.ImageFormat;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * 占位存储实现：仅生成唯一标识与 URL，不写入真实存储（MinIO 本期暂缓接入）。
 *
 * <p>接入 MinIO 时新增 {@code MinioStorageClient} 实现并通过配置切换激活，
 * 本类随之退役——{@link MediaStorageClient} 契约不变，Service 层零改动。</p>
 */
@Component
@RequiredArgsConstructor
public class PlaceholderStorageClient implements MediaStorageClient {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderStorageClient.class);

    private final MediaProperties properties;

    @Override
    public StoredMedia store(String userId, ImageFormat format, InputStream content, long size) {
        // TODO(MinIO): 替换为 putObject(key, content, size)；bucket 建议 turbofeed-media，
        //  私有读 + 预签名 URL（审核通过前不暴露公网直链），见 MediaController 设计注释第 3 点。
        String mediaId = properties.getKeyPrefix() + "/" + userId + "/"
                + UUID.randomUUID() + "." + format.extension();
        log.debug("占位存储（未真实落盘）: mediaId={}, size={}B", mediaId, size);
        return new StoredMedia(mediaId, properties.getPublicUrlBase() + mediaId);
    }
}
