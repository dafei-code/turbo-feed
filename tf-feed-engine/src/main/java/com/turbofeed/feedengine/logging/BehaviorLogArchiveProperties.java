package com.turbofeed.feedengine.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 行为明细归档（P1）配置：引擎侧每小时把已完成小时的 JSONL 压缩上传 MinIO。
 *
 * <p>全部字段默认「关闭 / 空」——默认值要按「谁承担后果」定：代码骨架的 {@code enabled=false}
 * 保护的是「任何人的机器都不该静默往外传数据」；真正打开是部署编排（prod compose / .env）显式声明的事。
 * 这与 {@code behavior-log.enabled} 同源（见 {@code behavior-data-pipeline.md} §2）。</p>
 *
 * <p><b>命名约定</b>：yml 用 kebab-case（{@code minio-endpoint}），本类用 camelCase
 * （{@code minioEndpoint}），Spring 松散绑定自动对应。环境变量名形如
 * {@code TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_ENDPOINT}（必须带 {@code BEHAVIOR_LOG}，
 * 原因同 behavior-log 本身的 FEED 陷阱：compose 只转发带前缀的名字进容器）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.behavior-log.archive")
public class BehaviorLogArchiveProperties {

    /** 是否启用归档上传。默认 false。 */
    private boolean enabled = false;

    /** MinIO / S3 兼容端点（如 http://minio:9000）。 */
    private String minioEndpoint = "";

    /** MinIO access key（由 TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_ACCESS_KEY 注入，禁止仓库字面量）。 */
    private String minioAccessKey = "";

    /** MinIO secret key（由 TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_SECRET_KEY 注入）。 */
    private String minioSecretKey = "";

    /** MinIO region（可选；留空表示用服务端默认 region）。 */
    private String minioRegion = "";

    /** 目标桶名。默认 turbo-feed。 */
    private String bucket = "turbo-feed";

    /** 对象 key 前缀；最终 key = {prefix}/dt=YYYY-MM-DD/hh=HH/{filename}.gz。默认 behavior。 */
    private String baseKeyPrefix = "behavior";

    /** 本地 JSONL 保留期（小时）：归档成功且超过该时长的本地文件被清理。默认 168（7 天）。 */
    private int localRetentionHours = 168;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMinioEndpoint() {
        return minioEndpoint;
    }

    public void setMinioEndpoint(String minioEndpoint) {
        this.minioEndpoint = minioEndpoint;
    }

    public String getMinioAccessKey() {
        return minioAccessKey;
    }

    public void setMinioAccessKey(String minioAccessKey) {
        this.minioAccessKey = minioAccessKey;
    }

    public String getMinioSecretKey() {
        return minioSecretKey;
    }

    public void setMinioSecretKey(String minioSecretKey) {
        this.minioSecretKey = minioSecretKey;
    }

    public String getMinioRegion() {
        return minioRegion;
    }

    public void setMinioRegion(String minioRegion) {
        this.minioRegion = minioRegion;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getBaseKeyPrefix() {
        return baseKeyPrefix;
    }

    public void setBaseKeyPrefix(String baseKeyPrefix) {
        this.baseKeyPrefix = baseKeyPrefix;
    }

    public int getLocalRetentionHours() {
        return localRetentionHours;
    }

    public void setLocalRetentionHours(int localRetentionHours) {
        this.localRetentionHours = localRetentionHours;
    }
}
