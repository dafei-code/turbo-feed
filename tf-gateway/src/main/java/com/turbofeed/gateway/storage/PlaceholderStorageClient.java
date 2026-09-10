package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.ImageFormat;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * 占位存储实现：仅生成唯一标识与 URL，不写入真实存储。
 *
 * <p><b>默认不激活</b>：本类与 {@link LocalDiskStorageClient} 均由
 * {@code turbofeed.media.storage} 条件装配（{@code local} / {@code placeholder}），
 * 严格互斥，避免同类型多实现导致注入歧义。默认值为 {@code local}（真实落盘，
 * 保证"上传 → 展示"链路可跑通）；需要回到纯占位行为时配置
 * {@code turbofeed.media.storage=placeholder}。</p>
 *
 * <p>接入 MinIO 时新增 {@code MinioStorageClient} 实现并通过配置切换激活，
 * 本类随之退役——{@link MediaStorageClient} 契约不变，Service 层零改动。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.media.storage", havingValue = "placeholder")
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

    /**
     * 占位存储无真实对象，删除为无操作（仅记录）。
     *
     * @param mediaId 内容唯一标识
     */
    @Override
    public void delete(String mediaId) {
        log.debug("占位存储无物理对象可删（无操作）: {}", mediaId);
    }
}
