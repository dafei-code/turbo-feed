package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.ImageFormat;
import com.turbofeed.gateway.storage.MediaStorageClient.StoredMedia;
import com.turbofeed.shared.result.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

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
public class MinioStorageClient implements MediaStorageClient, InitializingBean {

    private final MediaProperties properties;
    private volatile MinioClient client;
    private volatile boolean initialized = false;

    public MinioStorageClient(MediaProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时主动初始化 MinIO 客户端并预检桶策略。
     * <p>原来懒初始化到第一次上传时才设置桶策略，导致「只重启、不新上传」时旧图仍 403。
     * 改为启动期即完成桶创建 + 匿名读策略下发，确保重启后已有对象立即可读。</p>
     */
    @Override
    public void afterPropertiesSet() {
        client();
    }

    /** 懒初始化 MinIO 客户端 + 桶预检（双检锁，仅首次）。 */
    private MinioClient client() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    MediaProperties.Minio m = properties.getMinio();
                    requireCredentials(m);   // P0-1：启动期校验凭据外部化，缺失即启动失败
                    client = MinioClient.builder()
                            .endpoint(m.getEndpoint())
                            .credentials(m.getAccessKey(), m.getSecretKey())
                            .httpClient(buildOkHttpClient())   // 注入大连接池 OkHttp，消除默认 maxRequestsPerHost=5 的隐藏瓶颈
                            .build();
                    ensureBucket(m.getBucket());
                    initialized = true;
                }
            }
        }
        return client;
    }

    /**
     * 凭据外部化校验（P0-1）：accessKey/secretKey 必须来自环境变量注入，禁止任何仓库默认字面量。
     * 缺失即抛 {@link IllegalStateException} 导致启动失败——因本方法由 {@link #afterPropertiesSet()}
     * 在 Spring Context refresh 期调用，把「裸奔用默认 minioadmin 连 MinIO」的事故在启动期拦下，
     * 而非等首次上传才报 403/鉴权错。仅 {@code storage=minio} 激活时触发，local 存储不受影响。
     */
    private void requireCredentials(MediaProperties.Minio m) {
        if (!StringUtils.hasText(m.getAccessKey()) || !StringUtils.hasText(m.getSecretKey())) {
            throw new IllegalStateException(
                    "MinIO accessKey/secretKey 未配置：请通过环境变量 TURBOFEED_MINIO_ACCESS_KEY / "
                            + "TURBOFEED_MINIO_SECRET_KEY 注入，禁止在仓库默认配置中硬编码凭据。");
        }
    }

    /**
     * 自定义 OkHttpClient：抬高单主机并发上限，避免 MinIO（单 host）下默认
     * {@code Dispatcher.maxRequestsPerHost=5} 把上传线程串行化（与 Sentinel thread 上限无关）。
     */
    private OkHttpClient buildOkHttpClient() {
        Dispatcher dispatcher = new Dispatcher(new ThreadPoolExecutor(
                32, 128, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "minio-io");
                    t.setDaemon(true);
                    return t;
                }));
        dispatcher.setMaxRequests(1024);
        dispatcher.setMaxRequestsPerHost(256);   // 关键：默认 5
        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(256, 5, TimeUnit.MINUTES))
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** 桶预检：不存在则尝试创建（权限不足时仅告警，真正写入时再抛清晰异常）。 */
    private void ensureBucket(String bucket) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建: {}", bucket);
            }
            // 本地/演示：把桶设为匿名可读，使返回的 publicUrlBase+key 可被浏览器直接加载；
            // 生产应关闭（publicRead=false）并改用预签名 URL，避免对象公网裸奔。
            if (properties.getMinio().isPublicRead()) {
                setBucketPublicRead(bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 预检失败（写入时再校验）: bucket={}, {}", bucket, e.getMessage());
        }
    }

    /** 设置桶匿名读策略（仅 GetObject），使 publicUrlBase+key 可直接被浏览器加载。 */
    private void setBucketPublicRead(String bucket) {
        // MinIO/S3 策略：允许匿名对桶内所有对象做 GetObject
        // Principal 使用 {"AWS":"*"} 而非 "*"，兼容更多 MinIO 版本与旧版 S3 解析器
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"s3:GetObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
        try {
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
            log.info("MinIO bucket 已设为匿名可读: {}", bucket);
        } catch (Exception e) {
            // 设置失败只告警：已有对象仍可能 403，需在 MinIO 控制台手动开启匿名读
            log.warn("设置桶匿名读策略失败（图片可能 403，请在 MinIO 控制台手动开启匿名读）: bucket={}, policy={}", bucket, policy, e);
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

    /**
     * 物理删除对象（用户删除内容时调用）。
     *
     * <p>mediaId 即对象名（{@code media/{userId}/{uuid}.{ext}}）。删除失败（对象已不存在等）
     * 仅告警不抛，由调用方决定后续逻辑删除是否继续——避免物理层异常阻断用户的删除操作。</p>
     *
     * @param mediaId 对象名（即存储 key）
     */
    @Override
    public void delete(String mediaId) {
        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(mediaId)
                    .build());
            log.info("MinIO 对象已物理删除: {}", mediaId);
        } catch (Exception e) {
            log.warn("MinIO 对象删除失败（可能已不存在，继续逻辑删除）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }

    // ==================== 预签名直传（客户端直传，网关不收字节流） ====================

    @Override
    public String generateMediaId(String userId, ImageFormat format) {
        // 与 store() 同一拼装规则 {keyPrefix}/{userId}/{uuid}.{ext}：
        // userId 来自 JWT、uuid 服务端生成，客户端无法指定对象名（无路径穿越面）
        return properties.getKeyPrefix() + "/" + userId + "/" + UUID.randomUUID() + "." + format.extension();
    }

    @Override
    public String presignedPutUrl(String mediaId, String contentType, Duration expiry) {
        try {
            return client().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getMinio().getBucket())
                    .object(mediaId)
                    .expiry((int) expiry.getSeconds())
                    // Content-Type 纳入签名：客户端 PUT 必须携带一致的值，否则签名校验失败。
                    // 一石二鸟——既保证对象按真实图片类型存储（否则浏览器按 octet-stream 下载而非渲染），
                    // 也堵住「拿图片凭证上传非图片内容」。
                    .extraHeaders(Map.of("Content-Type", contentType))
                    .build());
        } catch (Exception e) {
            log.error("生成预签名上传 URL 失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成上传凭证失败");
        }
    }

    @Override
    public long sizeOf(String mediaId) {
        try {
            return client().statObject(StatObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(mediaId)
                    .build()).size();
        } catch (Exception e) {
            log.error("查询对象大小失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "校验上传对象失败");
        }
    }

    @Override
    public byte[] probeHeader(String mediaId, int bytes) {
        // 8.5.7 的 GetObjectArgs 无 offset/length 分段读（已字节码核实：ObjectReadArgs 仅有 ssec），
        // 故开流读前 N 字节后立即关闭：OkHttp 只取已消费的缓冲区，不会把整个对象拉下来。
        try (InputStream in = openStream(mediaId)) {
            byte[] header = new byte[bytes];
            int read = in.readNBytes(header, 0, bytes);
            return read == bytes ? header : Arrays.copyOf(header, Math.max(read, 0));
        } catch (Exception e) {
            log.error("探测对象头失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "校验上传对象失败");
        }
    }

    @Override
    public byte[] download(String mediaId) {
        try (InputStream in = openStream(mediaId)) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.error("下载对象失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传对象失败");
        }
    }

    @Override
    public void overwrite(String mediaId, byte[] content, String contentType) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            client().putObject(PutObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(mediaId)
                    .stream(in, content.length, -1)
                    .contentType(contentType)
                    .build());
            log.info("对象已覆盖写回: mediaId={}, size={}B", mediaId, content.length);
        } catch (Exception e) {
            log.error("对象覆盖写回失败: mediaId={}", mediaId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "处理产物回写失败");
        }
    }

    /** 打开对象读流（调用方负责关闭）。 */
    private InputStream openStream(String mediaId) throws Exception {
        return client().getObject(GetObjectArgs.builder()
                .bucket(properties.getMinio().getBucket())
                .object(mediaId)
                .build());
    }
}
