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
