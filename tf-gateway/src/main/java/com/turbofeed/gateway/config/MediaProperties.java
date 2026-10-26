package com.turbofeed.gateway.config;

import com.turbofeed.shared.redis.RedisKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Map;

/**
 * 媒体上传配置（{@code turbofeed.media.*}）。
 *
 * <p>所有上传限额外部化：调整阈值只改配置，不发版。</p>
 *
 * <p><b>结构（M1/3.5 归组后）</b>：绝大多数配置已按子域收进嵌套组——{@link Upload}、
 * {@link Processing}、{@link RateLimit}、{@link Minio}、{@link Presign}、{@link Mq}、
 * {@link CommentRisk}、{@link Signal}、{@link BehaviorLog}、{@link InteractionEvent}、
 * {@link Dedup}。新增配置请放进对应子域，不要往顶层堆。</p>
 *
 * <p><b>审核配置不在这里</b>：{@code turbofeed.media.review.*} 已独立为
 * {@code com.turbofeed.gateway.service.review.ReviewProperties}（636 行、34 个字段），
 * 与它服务的审核实现同包。本类因此不再 import 任何审核领域类型——此前为了那个嵌套类，
 * {@code config} 包反过来依赖了 {@code service.review} / {@code service.penalty}，方向是反的。</p>
 *
 * <p><b>为什么 keyPrefix / publicUrlBase / localDir / storage 仍留在顶层</b>：子域名与字段名
 * 会撞车——{@code storage} 既是「存储实现选择」（{@code local|placeholder|minio}）又是这一组的
 * 自然名字，归组后会变成 {@code turbofeed.media.storage.storage} 这种键。为了消除一个可读性
 * 问题而造一个更难看的键不值得，故这四个保持顶层，直到有更好的命名（如 {@code store}）再说。
 * <b>记在这里是为了说明「没做完」是判断而非遗漏。</b></p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.media")
public class MediaProperties {

    /** 对象存储 key 前缀，内容按用户隔离：{keyPrefix}/{userId}/{uuid}.{ext}。 */
    private String keyPrefix = "media";

    /** 上传侧限额（turbofeed.media.upload.*）：M1/3.5 从顶层扁平字段归组而来。 */
    private Upload upload = new Upload();

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


    /** MQ 审核事件 topic / consumerGroup（turbofeed.media.mq.*；可被 application.yml 覆盖，保留与代码常量一致的默认名）。 */
    private Mq mq = new Mq();

    /** 评论区异常感知配置（turbofeed.media.comment-risk.*，P1 #133）。 */
    private CommentRisk commentRisk = new CommentRisk();

    /** 审核信号外供 KV 配置（turbofeed.media.signal.*，P2-1）：命名空间与 TTL。 */
    private Signal signal = new Signal();

    /** 异常行为日志配置（turbofeed.media.behavior-log.*，P2-2）：开关 + 行为落库控制。 */
    private BehaviorLog behaviorLog = new BehaviorLog();

    /** 交互行为事件开关（A1 真双塔训练样本底座）：默认开，fail-open。 */
    private InteractionEvent interactionEvent = new InteractionEvent();

    /** 上传内容去重配置（#6 per-user SHA-256 去重）：开关 + 维度。默认开、per-user。 */
    private Dedup dedup = new Dedup();

    /** 图片处理链（Decorator：缩略图缩放）（turbofeed.media.processing.*）：M1/3.5 从顶层扁平字段归组而来。 */
    private Processing processing = new Processing();

    /** 预签名直传配置（turbofeed.media.presign.*）：仅对象存储实现（MinIO/S3）支持该链路。 */
    private Presign presign = new Presign();

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

        /** 本地兜底限流实例数预估（fallback 阈值下探用）：单实例阈值 = perUser / max(estimate,1)。
         *  多实例下 Redis 不可用走本地兜底时，若不下调则全局实际限流被放大成 perUser×N（过松）。
         *  运维按实际实例数设置（如 3），默认 1 表示不下探。 */
        private int fallbackInstanceEstimate = 1;

        public int getInflightTtlSeconds() {
            return inflightTtlSeconds;
        }

        public void setInflightTtlSeconds(int inflightTtlSeconds) {
            this.inflightTtlSeconds = inflightTtlSeconds;
        }

        public int getFallbackInstanceEstimate() {
            return fallbackInstanceEstimate;
        }

        public void setFallbackInstanceEstimate(int fallbackInstanceEstimate) {
            this.fallbackInstanceEstimate = fallbackInstanceEstimate;
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
        /** Access Key（必须由环境变量/密钥管理注入，无默认值，禁止硬编码，见 MinioStorageClient#requireCredentials） */
        private String accessKey;
        /** Secret Key（同上，无默认值） */
        private String secretKey;
        /** 媒体桶名（应用启动时会预检，不存在则尝试创建） */
        private String bucket = "turbo-feed-media";

        /** 桶匿名可读（本地/演示用）：true 时启动预检把桶策略设为公开读，
         * 使返回的 publicUrlBase+key 可被浏览器直接加载。
         * <b>默认 false（安全优先）</b>：私有桶下读取改走预签名 URL（见 {@code MinioStorageClient#presignedGetUrl}）；
         * prod 环境若被显式置 true，{@code MinioStorageClient} 启动即抛异常，杜绝对象公网裸奔。 */
        private boolean publicRead = false;

        /** 读取预签名 URL 有效期（秒）：私有桶下返回给客户端的 GET 直链有效期，过期后需重新获取。默认 900（15 分钟）。 */
        private int urlExpirySeconds = 900;

        public int getUrlExpirySeconds() {
            return urlExpirySeconds;
        }

        public void setUrlExpirySeconds(int urlExpirySeconds) {
            this.urlExpirySeconds = urlExpirySeconds;
        }

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

        public boolean isPublicRead() {
            return publicRead;
        }

        public void setPublicRead(boolean publicRead) {
            this.publicRead = publicRead;
        }
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Mq getMq() {
        return mq;
    }

    public void setMq(Mq mq) {
        this.mq = mq;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }

    public Processing getProcessing() {
        return processing;
    }

    public void setProcessing(Processing processing) {
        this.processing = processing;
    }

    // ===== 旧扁平键的兼容代理（deprecated）=====
    //
    // 这几个键在 M1/3.5 被归入 upload.* / processing.* 子域。配置是【对外契约】：线上 yml、
    // 运维脚本、K8s ConfigMap 都在用旧键，直接删等于强制所有环境同步发版。因此旧键保留为
    // 委托访问器——getter 读新组、setter 写新组，两者永远指向同一份值，不存在「两处配置漂移」。
    //
    // ⚠️ 不要同时配置新旧键：Spring 的绑定顺序不保证，同时配置且值不同时以最后绑定者为准。

    /**
     * @deprecated 用 {@code turbofeed.media.upload.max-file-size}（{@link Upload#getMaxFileSize()}）。
     *     保留仅为兼容旧配置键，内部已委托到新组。
     */
    @Deprecated
    public DataSize getMaxFileSize() {
        return upload.getMaxFileSize();
    }

    /**
     * @deprecated 用 {@code turbofeed.media.upload.max-file-size}。写入会同步进 {@link Upload}。
     */
    @Deprecated
    public void setMaxFileSize(DataSize maxFileSize) {
        upload.setMaxFileSize(maxFileSize);
    }

    /**
     * @deprecated 用 {@code turbofeed.media.upload.max-batch-count}（{@link Upload#getMaxBatchCount()}）。
     */
    @Deprecated
    public int getMaxBatchCount() {
        return upload.getMaxBatchCount();
    }

    /**
     * @deprecated 用 {@code turbofeed.media.upload.max-batch-count}。
     */
    @Deprecated
    public void setMaxBatchCount(int maxBatchCount) {
        upload.setMaxBatchCount(maxBatchCount);
    }

    /**
     * @deprecated 用 {@code turbofeed.media.upload.idempotent-ttl-seconds}
     *     （{@link Upload#getIdempotentTtlSeconds()}）。
     */
    @Deprecated
    public int getIdempotentTtlSeconds() {
        return upload.getIdempotentTtlSeconds();
    }

    /**
     * @deprecated 用 {@code turbofeed.media.upload.idempotent-ttl-seconds}。
     */
    @Deprecated
    public void setIdempotentTtlSeconds(int idempotentTtlSeconds) {
        upload.setIdempotentTtlSeconds(idempotentTtlSeconds);
    }

    /**
     * @deprecated 用 {@code turbofeed.media.processing.enabled}（{@link Processing#isEnabled()}）。
     */
    @Deprecated
    public boolean isProcessingEnabled() {
        return processing.isEnabled();
    }

    /**
     * @deprecated 用 {@code turbofeed.media.processing.enabled}。
     */
    @Deprecated
    public void setProcessingEnabled(boolean processingEnabled) {
        processing.setEnabled(processingEnabled);
    }

    /**
     * @deprecated 用 {@code turbofeed.media.processing.thumbnail-max-dimension}
     *     （{@link Processing#getThumbnailMaxDimension()}）。
     */
    @Deprecated
    public long getThumbnailMaxDimension() {
        return processing.getThumbnailMaxDimension();
    }

    /**
     * @deprecated 用 {@code turbofeed.media.processing.thumbnail-max-dimension}。
     */
    @Deprecated
    public void setThumbnailMaxDimension(long thumbnailMaxDimension) {
        processing.setThumbnailMaxDimension(thumbnailMaxDimension);
    }

    public Minio getMinio() {
        return minio;
    }

    public void setMinio(Minio minio) {
        this.minio = minio;
    }

    public Presign getPresign() {
        return presign;
    }

    public void setPresign(Presign presign) {
        this.presign = presign;
    }

    /**
     * 预签名直传配置（turbofeed.media.presign.*）。
     *
     * <p>客户端直传对象存储模式下，网关只签发凭证、不收字节流；凭证有效期是唯一的
     * 「未完成上传」时间窗，过期后客户端必须重新申请（重签会复用同一批对象名，见
     * {@code UploadReservationStore#saveRequestIndex}），避免孤儿对象随重试翻倍。</p>
     */
    public static class Presign {

        /** 预签名 URL 有效期（秒）：须覆盖「最慢一张传完」的时间，过长则扩大凭证泄露窗口。 */
        private int expirySeconds = 300;

        public int getExpirySeconds() {
            return expirySeconds;
        }

        public void setExpirySeconds(int expirySeconds) {
            this.expirySeconds = expirySeconds;
        }

        /** 是否启用孤儿对象清理（客户端传完却没通知完成 / 进程崩溃残留的对象）。默认开启。 */
        private boolean orphanCleanupEnabled = true;

        /** 单次清理扫描的孤儿上限（防止一次拉爆对象存储）。 */
        private int orphanCleanupBatch = 200;

        public boolean isOrphanCleanupEnabled() {
            return orphanCleanupEnabled;
        }

        public void setOrphanCleanupEnabled(boolean orphanCleanupEnabled) {
            this.orphanCleanupEnabled = orphanCleanupEnabled;
        }

        public int getOrphanCleanupBatch() {
            return orphanCleanupBatch;
        }

        public void setOrphanCleanupBatch(int orphanCleanupBatch) {
            this.orphanCleanupBatch = orphanCleanupBatch;
        }
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
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

    /**
     * MQ 审核事件配置（turbofeed.media.mq.*）：topic 与 consumerGroup 外部化，
     * 保留与 {@code RocketMqMediaEventPublisher.TOPIC} / 默认消费组一致的默认名，可被 application.yml 覆盖。
     */
    public static class Mq {
        /** 媒体上传事件 Topic（发布器与消费者共用，须与消费者占位符默认一致）。 */
        private String topic = "turbofeed-media-uploaded";

        /** 审核事件消费者组（RocketMQ 消费集群隔离与偏移管理单位）。 */
        private String consumerGroup = "turbofeed-media-review-group";

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }
    }

    public CommentRisk getCommentRisk() {
        return commentRisk;
    }

    public void setCommentRisk(CommentRisk commentRisk) {
        this.commentRisk = commentRisk;
    }

    public Signal getSignal() {
        return signal;
    }

    public void setSignal(Signal signal) {
        this.signal = signal;
    }

    public BehaviorLog getBehaviorLog() {
        return behaviorLog;
    }

    public void setBehaviorLog(BehaviorLog behaviorLog) {
        this.behaviorLog = behaviorLog;
    }

    public InteractionEvent getInteractionEvent() {
        return interactionEvent;
    }

    public void setInteractionEvent(InteractionEvent interactionEvent) {
        this.interactionEvent = interactionEvent;
    }

    public Dedup getDedup() {
        return dedup;
    }

    public void setDedup(Dedup dedup) {
        this.dedup = dedup;
    }

    /**
     * 评论区异常感知配置（turbofeed.media.comment-risk.*，P1 #133）。
     *
     * <p>不依赖评论内容深度解析，仅做速率/聚集异常感知：单账号刷评限流、评论区爆发折叠+进巡查队列。</p>
     */
    public static class CommentRisk {
        /** 内容评论速率窗口（秒）：近窗口内某内容评论数达阈值即视为评论区爆发。默认 60 */
        private int windowSeconds = 60;

        /** 内容评论速率阈值：近 windowSeconds 内某内容评论数超此值 → 评论区爆发（折叠+进复审）。默认 50 */
        private int rateThreshold = 50;

        /** 单账号对单内容评论频控窗口（秒）。默认 60 */
        private int userWindowSeconds = 60;

        /** 单账号对单内容评论频次上限：超此值 → 限流该账号（拒评）。默认 10 */
        private int userLimit = 10;

        /** 速率异常时是否自动折叠（FOLDED，默认不展示）+ 进复审。默认 true */
        private boolean foldOnSpike = true;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getRateThreshold() {
            return rateThreshold;
        }

        public void setRateThreshold(int rateThreshold) {
            this.rateThreshold = rateThreshold;
        }

        public int getUserWindowSeconds() {
            return userWindowSeconds;
        }

        public void setUserWindowSeconds(int userWindowSeconds) {
            this.userWindowSeconds = userWindowSeconds;
        }

        public int getUserLimit() {
            return userLimit;
        }

        public void setUserLimit(int userLimit) {
            this.userLimit = userLimit;
        }

        public boolean isFoldOnSpike() {
            return foldOnSpike;
        }

        public void setFoldOnSpike(boolean foldOnSpike) {
            this.foldOnSpike = foldOnSpike;
        }
    }

    /**
     * 审核信号外供 KV 配置（turbofeed.media.signal.*，P2-1）。
     *
     * <p>抖音式「审核与推荐解耦」：turbo-feed 把内容处置/账号健康分/举报人信用写成 Redis KV，
     * 上游推荐系统直读做召回过滤与排序降权，不直连审核 MySQL。本配置控制命名空间前缀与各信号 TTL。</p>
     */
    public static class Signal {
        /** KV 命名空间前缀（所有审核信号键的前缀）。默认 tf:mod:，避免与现有 tf:report:/tf:comment:/tf:media: 混用。 */
        private String namespace = RedisKeys.MODERATION_DEFAULT_NAMESPACE;

        /** 内容处置信号 TTL（秒）：随内容生命周期，默认 30 天可刷新。0 表示不设过期（不推荐）。 */
        private long mediaTtlSeconds = 2592000L;

        /** 账号健康分信号 TTL（秒）：刷新式，默认 0=不过期（每次写覆盖）。 */
        private long accountTtlSeconds = 0L;

        /** 举报人信用信号 TTL（秒）：刷新式，默认 0=不过期。 */
        private long reporterTtlSeconds = 0L;

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public long getMediaTtlSeconds() {
            return mediaTtlSeconds;
        }

        public void setMediaTtlSeconds(long mediaTtlSeconds) {
            this.mediaTtlSeconds = mediaTtlSeconds;
        }

        public long getAccountTtlSeconds() {
            return accountTtlSeconds;
        }

        public void setAccountTtlSeconds(long accountTtlSeconds) {
            this.accountTtlSeconds = accountTtlSeconds;
        }

        public long getReporterTtlSeconds() {
            return reporterTtlSeconds;
        }

        public void setReporterTtlSeconds(long reporterTtlSeconds) {
            this.reporterTtlSeconds = reporterTtlSeconds;
        }
    }

    /**
     * 异常行为日志配置（turbofeed.media.behavior-log.*，P2-2）。
     *
     * <p>把审核相关行为（举报 / 评论异常 / 处置）持久化到 behavior_log 单表，供 P2-1 审核信号做闭环来源
     * （离线跑恶意账号聚类、举报人信用再训练、全局举报水位因子校准）。开关默认开，fail-open：
     * 写入失败仅记日志、不影响主流程。</p>
     */
    public static class BehaviorLog {
        /** 是否启用行为日志写入（排障可置 false 一键停用）。默认 true。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 交互行为事件配置（A1 真双塔召回的训练样本底座）。
     *
     * <p>把用户对内容的互动反馈（曝光/观看/完播/点赞/评论/分享/不感兴趣）持久化到
     * interaction_event 单表，供离线双塔作业产出 (user, item, label) 三元组。开关默认开，
     * fail-open：写入失败仅记日志、不影响推荐/互动主流程。</p>
     */
    public static class InteractionEvent {
        /** 是否启用交互事件写入（排障可置 false 一键停用）。默认 true。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 上传内容去重配置（#6 per-user SHA-256 去重）。
     *
     * <p><b>为什么做</b>：同一用户反复传同一张图（重试 / 误点 / 多帖引用）会在对象存储里产生
     * N 份相同字节、N 行 media 记录，浪费存储且后续删除互相牵连。上传主链路在落库前先算内容指纹，
     * 命中则跳过对象存储写入、复用首份的 URL（新帖用「别名 mediaId」指向它），既省空间又保留帖结构。</p>
     *
     * <p><b>维度</b>：{@code PER_USER}（默认）按 (user_id, 内容哈希) 去重——只拦同用户自己的重复，
     * 避免跨用户对象共享带来的「删 A 图 B 裂」删除耦合；{@code GLOBAL} 跨用户复用（仅靠哈希点查），
     * 节省更彻底但接受跨用户删除耦合，生产按需开启。</p>
     *
     * <p><b>失败方向</b>：整条去重链 fail-open——算哈希/查表/记表任一异常都只告警、退化为「不去重」，
     * 绝不阻断上传主流程（Storage 才是内容真身，指纹表只是优化）。</p>
     */
    public static class Dedup {
        /** 是否启用上传去重。默认 true。排障可置 false 一键关闭（退化为不复用、不记账）。 */
        private boolean enabled = true;

        /**
         * 去重维度：PER_USER（默认，按用户隔离） / GLOBAL（跨用户复用）。
         * 仅影响「命中查谁」——GLOBAL 下不同用户的相同内容也复用首份对象。
         */
        private DedupScope scope = DedupScope.PER_USER;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public DedupScope getScope() {
            return scope;
        }

        public void setScope(DedupScope scope) {
            this.scope = scope;
        }
    }

    /**
     * 上传侧限额（{@code turbofeed.media.upload.*}）。
     *
     * <p><b>M1/3.5 归组</b>：这三个字段原先散在顶层（{@code turbofeed.media.max-file-size} 等），
     * 与存储、审核、限流等十几个键平铺在一起，找「上传能传多大」要在 1300 行里翻。归组后
     * 配置树按子域收敛：<b>新增上传相关开关有明确归属</b>，而不是继续往顶层堆。旧键保留为
     * deprecated 委托访问器（见 {@link #getMaxFileSize()}），线上配置无需同步发版。</p>
     */
    public static class Upload {

        /** 单文件大小上限（Spring DataSize 语法，如 5MB）。 */
        private DataSize maxFileSize = DataSize.ofMegabytes(5);

        /** 单次批量上传文件数上限。 */
        private int maxBatchCount = 9;

        /** 上传幂等去重 TTL（秒）：同一 X-Request-Id 在该窗口内重复提交直接返回首次结果（结合 userId 防越权）。 */
        private int idempotentTtlSeconds = 5;

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

        public int getIdempotentTtlSeconds() {
            return idempotentTtlSeconds;
        }

        public void setIdempotentTtlSeconds(int idempotentTtlSeconds) {
            this.idempotentTtlSeconds = idempotentTtlSeconds;
        }
    }

    /**
     * 图片处理链（{@code turbofeed.media.processing.*}）。
     *
     * <p><b>M1/3.5 归组</b>：原为顶层 {@code processing-enabled} / {@code thumbnail-max-dimension}。
     * 归组理由同 {@link Upload}。开关与它的参数放在一起，避免「开了处理链却找不到长边上限在哪配」。</p>
     */
    public static class Processing {

        /**
         * 图片处理链开关（Decorator：缩略图缩放）。默认关闭以保持流式零内存路径；
         * 开启后经 ImageIO 解码重编码，会引入解码内存开销（12MP ARGB ≈ 48MB/张），需评估 QPS 与堆内存。
         */
        private boolean enabled = false;

        /** 缩略图长边上限（px），超过则等比缩放。 */
        private long thumbnailMaxDimension = 2048;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getThumbnailMaxDimension() {
            return thumbnailMaxDimension;
        }

        public void setThumbnailMaxDimension(long thumbnailMaxDimension) {
            this.thumbnailMaxDimension = thumbnailMaxDimension;
        }
    }

    /** 上传去重维度。 */
    public enum DedupScope {
        /** 按 (user_id, 内容哈希) 去重：只拦同用户自己重复，避免跨用户删除耦合（v1 默认）。 */
        PER_USER,
        /** 按内容哈希全局去重：跨用户复用首份对象，节省更彻底但接受跨用户删除耦合。 */
        GLOBAL
    }
}
