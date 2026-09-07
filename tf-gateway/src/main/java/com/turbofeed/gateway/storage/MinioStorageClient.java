package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.ImageFormat;
import com.turbofeed.shared.result.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * 对象存储实现（MinIO / 兼容 S3 协议）：百亿级文件的唯一可行落点。
 *
 * <p><b>为什么必须有它</b>：本地磁盘存储（{@link LocalDiskStorageClient}）是单机目录，
 * 百亿文件、PB 级容量根本无法承载，且多实例不共享。对象存储（MinIO Server Pool / 云 COS）
 * 天然横向扩容，URL 直接指向公网或 CDN，网关不过字节流——这正是抖音级媒体的标准架构。</p>
 *
 * <p><b>防腐层契约</b>：实现 {@link MediaStorageClient} 端口，{@code Service} 层零改动；
 * 切换只改 {@code turbofeed.media.storage=minio}（默认仍是 {@code local}，克隆即跑）。</p>
 *
 * <p><b>可见性</b>：默认返回 {@code publicUrlBase + key}（假设桶为只读/公网或前置 CDN），
 * 与本地磁盘的"公开可读"语义一致。若需"审核通过前不暴露"，将桶设为私有 + 开启预签名 URL
 * （读取侧签名），属后续增强，不阻塞本次规模改造。</p>
 *
 * <p><b>写入安全</b>：key 由服务端 {@code {keyPrefix}/{userId}/{uuid}.{ext}} 拼装，
 * userId 来自 JWT（服务端），uuid 服务端生成——杜绝路径穿越；content-type 以真实
 * Magic Number 判定值设置，浏览器按图片解析。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "turbofeed.media.storage", havingValue = "minio")
public class MinioStorageClient implements MediaStorageClient {

    private final MediaProperties properties;
    private volatile MinioClient client;
    private volatile boolean initialized = false;

    public MinioStorageClient(MediaProperties properties) {
        this.properties = properties;
    }

    /** 懒初始化 MinIO 客户端 + 桶预检（双检锁，仅首次）。 */
    private MinioClient client() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    MediaProperties.Minio m = properties.getMinio();
                    client = MinioClient.builder()
                            .endpoint(m.getEndpoint())
                            .credentials(m.getAccessKey(), m.getSecretKey())
                            .build();
                    ensureBucket(m.getBucket());
                    initialized = true;
                }
            }
        }
        return client;
    }

    /** 桶预检：不存在则尝试创建（权限不足时仅告警，真正写入时再抛清晰异常）。 */
    private void ensureBucket(String bucket) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 预检失败（写入时再校验）: bucket={}, {}", bucket, e.getMessage());
        }
    }

    @Override
    public StoredMedia store(String userId, ImageFormat format, InputStream content, long size) {
        MediaProperties.Minio m = properties.getMinio();
        String mediaId = properties.getKeyPrefix() + "/" + userId + "/"
                + UUID.randomUUID() + "." + format.extension();
        try {
            // partSize=-1 表示单 PUT（≤5MB 适用）；objectSize 必须已知
            client().putObject(PutObjectArgs.builder()
                    .bucket(m.getBucket())
                    .object(mediaId)
                    .stream(content, size, -1)
                    .contentType(format.contentType())
                    .build());
            log.info("媒体落对象存储: mediaId={}, size={}B, bucket={}", mediaId, size, m.getBucket());
        } catch (Exception e) {
            log.error("MinIO 写入失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件写入对象存储失败");
        }
        return new StoredMedia(mediaId, properties.getPublicUrlBase() + mediaId);
    }
}
