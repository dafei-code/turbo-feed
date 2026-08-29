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

    /** 占位存储返回的公网 URL 前缀，MinIO/COS 接入后由存储实现自行拼装替代。 */
    private String publicUrlBase = "https://oss.turbofeed.com/";

    /** 图片处理链开关（Decorator：缩略图缩放）。默认关闭以保持流式零内存路径；
     *  开启后经 ImageIO 解码重编码，会引入解码内存开销（12MP ARGB ≈ 48MB/张），需评估 QPS 与堆内存。 */
    private boolean processingEnabled = false;

    /** 缩略图长边上限（px），超过则等比缩放（turbofeed.media.thumbnail-max-dimension）。 */
    private long thumbnailMaxDimension = 2048;

    /** 上传限流阈值（turbofeed.media.rate-limit.*；机器维度 Sentinel + 用户维度 Redis 分工）。 */
    private RateLimit rateLimit = new RateLimit();

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
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
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
}
