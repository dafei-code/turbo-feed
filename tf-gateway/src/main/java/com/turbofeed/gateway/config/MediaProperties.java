package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 媒体上传配置（turbofeed.media.*）。
 *
 * <p>所有上传限额外部化：调整阈值只改配置，不发版。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.media")
public class MediaProperties {

    /** 对象存储 key 前缀，内容按用户隔离：{keyPrefix}/{userId}/{uuid}.{ext}。 */
    private String keyPrefix = "media";

    /** 单文件大小上限（Spring DataSize 语法，如 5MB）。 */
    private DataSize maxFileSize = DataSize.ofMegabytes(5);

    /** 单次批量上传文件数上限。 */
    private int maxBatchCount = 9;

    /**
     * 对外访问基址：存储实现拼装返回 URL 时使用的前缀。
     *
     * <p>本地磁盘存储（默认）下应指向本服务地址（如 {@code http://localhost:8080/}），
     * 使返回的 URL 形如 {@code http://localhost:8080/media/{uid}/{uuid}.ext} 可直接被浏览器加载；
     * MinIO/COS 接入后改为对象存储的公网域名或 CDN 域名，由存储实现自行拼装。</p>
     */
    private String publicUrlBase = "https://oss.turbofeed.com/";

    /**
     * 本地磁盘存储根目录（仅 {@code turbofeed.media.storage=local} 时生效）。
     *
     * <p>落盘路径为 {@code {localDir}/{userId}/{uuid}.{ext}}，配合
     * {@code WebConfig} 的 {@code /media/**} 静态映射对外提供访问。
     * 相对路径以进程工作目录为基准，生产请改为绝对路径或挂载卷。</p>
     */
    private String localDir = "./data/media";

    /**
     * 存储实现选择：local（本地磁盘，默认，克隆即跑）/ placeholder（仅拼 URL 不落盘）/ minio（对象存储，百亿级）。
     * 三个实现均通过 {@code @ConditionalOnProperty} 与本值绑定，严格互斥。
     */
    private String storage = "local";

    /** 对象存储（MinIO/S3）连接配置：仅 {@code storage=minio} 时生效。 */
    private Minio minio = new Minio();

    /** 上传限流阈值（turbofeed.media.rate-limit.*；机器维度 Sentinel + 用户维度 Redis 分工）。 */
    private RateLimit rateLimit = new RateLimit();

    /** 上传幂等去重 TTL（秒）：同一 X-Request-Id 在该窗口内重复提交直接返回首次结果（结合 userId 防越权）。 */
    private int idempotentTtlSeconds = 5;

    /** 图片处理链开关（Decorator：缩略图缩放）。默认关闭以保持流式零内存路径；
     *  开启后经 ImageIO 解码重编码，会引入解码内存开销（12MP ARGB ≈ 48MB/张），需评估 QPS 与堆内存。 */
    private boolean processingEnabled = false;

    /** 缩略图长边上限（px），超过则等比缩放（turbofeed.media.thumbnail-max-dimension）。 */
    private long thumbnailMaxDimension = 2048;

    /**
     * 上传接口限流阈值（机器维度与用户维度分工）。
     *
     * <p>上传是 IO 型接口：单靠 QPS 只能防频次，防不了体积与线程堆积，故并发与 QPS 双维度
     * 并用——两条均为 Sentinel 资源级规则，注解埋点于 MediaUploadService#upload
     * （@SentinelResource）。用户级阈值 per-user 由 Redis UploadRateLimiter 消费：
     * 注解模式的热点参数只能取方法签名参数，取不到 ThreadLocal 的 userId，
     * 用户维度限流不走 Sentinel，由 Redis 跨实例统一计数。</p>
     */
    public static class RateLimit {

        /** 单机并发执行数上限（FLOW_GRADE_THREAD）：防慢存储（MinIO 抖动）导致线程堆积拖垮进程。 */
        private int thread = 20;

        /** 单机 QPS 上限（FLOW_GRADE_QPS）：防总量刷爆磁盘带宽。 */
        private int qps = 100;

        /** 单用户限流阈值（Redis UploadRateLimiter 消费）：防单用户持续刷接口；时间窗口径由限流器实现决定。 */
        private int perUser = 10;

        /** 用户维度限流时间窗口（秒）：与 perUser 配合，例如 60s 内最多 perUser 次（固定窗口实现，边界突刺为已知取舍）。 */
        private int windowSeconds = 60;

        /** 上传并发占位 TTL（秒，ConcurrentUploadValidator）：进程崩溃 / 释放失败时的兜底过期时间。 */
        private int inflightTtlSeconds = 30;

        public int getInflightTtlSeconds() {
            return inflightTtlSeconds;
        }

        public void setInflightTtlSeconds(int inflightTtlSeconds) {
            this.inflightTtlSeconds = inflightTtlSeconds;
        }

        public int getThread() {
            return thread;
        }

        public void setThread(int thread) {
            this.thread = thread;
        }

        public int getQps() {
            return qps;
        }

        public void setQps(int qps) {
            this.qps = qps;
        }

        public int getPerUser() {
            return perUser;
        }

        public void setPerUser(int perUser) {
            this.perUser = perUser;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /**
     * 对象存储连接配置（MinIO / 兼容 S3 协议）。
     *
     * <p>百亿级文件的唯一可行落点：对象存储天然横向扩容（MinIO Server Pool / 云 COS），
     * 代码不动即可扩容量。本配置仅供本地/开发；生产用环境变量或密钥管理注入
     * accessKey/secretKey，勿硬编码。</p>
     */
    public static class Minio {
        /** MinIO 服务地址（含协议与端口），如 http://127.0.0.1:9000 */
        private String endpoint = "http://127.0.0.1:9000";
        /** Access Key（生产用环境变量/密钥管理注入，勿硬编码） */
        private String accessKey = "";
        /** Secret Key */
        private String secretKey = "";
        /** 媒体桶名（应用启动时会预检，不存在则尝试创建） */
        private String bucket = "turbo-feed-media";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public int getIdempotentTtlSeconds() {
        return idempotentTtlSeconds;
    }

    public void setIdempotentTtlSeconds(int idempotentTtlSeconds) {
        this.idempotentTtlSeconds = idempotentTtlSeconds;
    }

    public Minio getMinio() {
        return minio;
    }

    public void setMinio(Minio minio) {
        this.minio = minio;
    }

    public boolean isProcessingEnabled() {
        return processingEnabled;
    }

    public void setProcessingEnabled(boolean processingEnabled) {
        this.processingEnabled = processingEnabled;
    }

    public long getThumbnailMaxDimension() {
        return thumbnailMaxDimension;
    }

    public void setThumbnailMaxDimension(long thumbnailMaxDimension) {
        this.thumbnailMaxDimension = thumbnailMaxDimension;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxBatchCount() {
        return maxBatchCount;
    }

    public void setMaxBatchCount(int maxBatchCount) {
        this.maxBatchCount = maxBatchCount;
    }

    public String getPublicUrlBase() {
        return publicUrlBase;
    }

    public void setPublicUrlBase(String publicUrlBase) {
        this.publicUrlBase = publicUrlBase;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }
}
