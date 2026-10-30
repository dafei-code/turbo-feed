package com.turbofeed.feedengine.logging;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 行为明细归档的 MinIO / S3 兼容实现（防腐层 {@link BehaviorLogUploader}）。
 *
 * <p><b>双检锁懒初始化</b>：首次上传时才建客户端，避免"仅仅引入了依赖、实际没开归档"也去连 MinIO
 * （克隆即跑、连不上就报错会破坏开发体验）。启动期仅做<b>最佳努力</b>的桶预检（失败只告警，
 * 不阻断应用——归档是旁路链路，不该拖垮 engine）。</p>
 *
 * <p><b>fail-open 边界</b>：{@link #upload} 把 MinIO 的任意异常包成 {@link IOException} 上抛，
 * 由 {@link BehaviorLogArchiver} 捕获并留待下个周期重试（本地文件不删、不标记 archived）。</p>
 *
 * <p><b>凭据外部化</b>：endpoint / accessKey / secretKey 全部来自 {@link BehaviorLogArchiveProperties}
 * （环境变量注入），本类不写任何仓库字面量。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "turbofeed.feed.behavior-log.archive", name = "enabled", havingValue = "true")
public class MinioBehaviorLogUploader implements BehaviorLogUploader {

    private final BehaviorLogArchiveProperties props;
    private final boolean logContent;

    private volatile MinioClient client;
    private volatile boolean initialized = false;

    public MinioBehaviorLogUploader(BehaviorLogArchiveProperties props,
                                   @Value("${turbofeed.feed.behavior-log.archive.log-content:true}") boolean logContent) {
        this.props = props;
        this.logContent = logContent;
    }

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            return;
        }
        if (!hasCredentials()) {
            log.warn("行为明细归档：MinIO 凭据缺失（endpoint/accessKey/secretKey 之一为空），"
                    + "归档将一直失败直到补上；本地文件会保留待重试");
        }
        // 最佳努力：建客户端 + 桶预检。失败不抛，归档器会在上传时再试。
        try {
            client();
        } catch (Exception e) {
            log.warn("行为明细归档：MinIO 客户端初始化/桶预检失败（上传时再校验）: {}", e.getMessage());
        }
    }

    private boolean hasCredentials() {
        return props.getMinioEndpoint() != null && !props.getMinioEndpoint().isEmpty()
                && props.getMinioAccessKey() != null && !props.getMinioAccessKey().isEmpty()
                && props.getMinioSecretKey() != null && !props.getMinioSecretKey().isEmpty();
    }

    /** 双检锁懒初始化 MinIO 客户端 + 桶预检（仅首次）。 */
    private MinioClient client() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    MinioClient.Builder b = MinioClient.builder()
                            .endpoint(props.getMinioEndpoint())
                            .credentials(props.getMinioAccessKey(), props.getMinioSecretKey());
                    if (props.getMinioRegion() != null && !props.getMinioRegion().isEmpty()) {
                        b.region(props.getMinioRegion());
                    }
                    client = b.build();
                    ensureBucket();
                    initialized = true;
                }
            }
        }
        return client;
    }

    /** 桶预检：不存在则尝试创建（权限不足时仅告警）。 */
    private void ensureBucket() {
        try {
            if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(props.getBucket()).build())) {
                client.makeBucket(io.minio.MakeBucketArgs.builder().bucket(props.getBucket()).build());
                log.info("行为明细归档：MinIO bucket 已创建: {}", props.getBucket());
            }
        } catch (Exception e) {
            log.warn("行为明细归档：MinIO bucket 预检失败（上传时再校验）: bucket={}, {}", props.getBucket(), e.getMessage());
        }
    }

    @Override
    public void upload(Path gzFile, String objectKey) throws IOException {
        if (!hasCredentials()) {
            throw new IOException("MinIO 凭据缺失，无法上传: key=" + objectKey);
        }
        long size = Files.size(gzFile);
        try (InputStream in = Files.newInputStream(gzFile)) {
            client().putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .stream(in, size, -1)
                    .contentType("application/gzip")
                    .build());
            if (logContent) {
                log.info("行为明细已上传 MinIO: key={}, size={}B, bucket={}", objectKey, size, props.getBucket());
            }
        } catch (Exception e) {
            // 包成 IOException，归档器据此判定"本次未成功"
            throw new IOException("MinIO 上传失败: key=" + objectKey + ", " + e.getMessage(), e);
        }
    }
}
