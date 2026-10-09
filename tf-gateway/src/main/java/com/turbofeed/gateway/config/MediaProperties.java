package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.review.ContentModeration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.turbofeed.gateway.service.review.ModerationMode;

import java.util.List;
import java.util.Map;

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

    /** MQ 审核事件 topic / consumerGroup（turbofeed.media.mq.*；可被 application.yml 覆盖，保留与代码常量一致的默认名）。 */
    private Mq mq = new Mq();

    /** 审核配置（turbofeed.media.review.*）：auto-pass 为演示占位自动放行，关闭后走人工审核闸。 */
    private Review review = new Review();

    /** 评论区异常感知配置（turbofeed.media.comment-risk.*，P1 #133）。 */
    private CommentRisk commentRisk = new CommentRisk();

    /** 审核信号外供 KV 配置（turbofeed.media.signal.*，P2-1）：命名空间与 TTL。 */
    private Signal signal = new Signal();

    /** 异常行为日志配置（turbofeed.media.behavior-log.*，P2-2）：开关 + 行为落库控制。 */
    private BehaviorLog behaviorLog = new BehaviorLog();

    /** 图片处理链开关（Decorator：缩略图缩放）。默认关闭以保持流式零内存路径；
     *  开启后经 ImageIO 解码重编码，会引入解码内存开销（12MP ARGB ≈ 48MB/张），需评估 QPS 与堆内存。 */
    private boolean processingEnabled = false;

    /** 缩略图长边上限（px），超过则等比缩放（turbofeed.media.thumbnail-max-dimension）。 */
    private long thumbnailMaxDimension = 2048;

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
        /** Access Key（必须由环境变量/密钥管理注入，无默认值，禁止硬编码，见 MinioStorageClient#requireCredentials） */
        private String accessKey;
        /** Secret Key（同上，无默认值） */
        private String secretKey;
        /** 媒体桶名（应用启动时会预检，不存在则尝试创建） */
        private String bucket = "turbo-feed-media";

        /** 桶匿名可读（本地/演示用）：true 时启动预检把桶策略设为公开读，
         * 使返回的 publicUrlBase+key 可被浏览器直接加载。生产应改 false 并用预签名 URL。 */
        private boolean publicRead = true;

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

    /** 审核配置（turbofeed.media.review.*）。 */
    public Review getReview() {
        return review;
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

    /**
     * 审核配置（turbofeed.media.review.*）。
     *
     * <p>UGC 内容必须审核。默认 {@code auto-pass=false} 表示机审桩（AutoPassModeration）
     * 不自动放行，上传后停在 PENDING，由管理员审核接口（{@code /api/admin/media/{mediaId}/review}）
     * 放行/驳回——即「真审核」闸。设为 {@code true} 退回演示占位（机审直接过），仅供本地联调
     * / 克隆即跑；生产务必关闭，否则 UGC 内容裸奔涉政涉黄。</p>
     */
    public static class Review {
        /** 机审自动放行（true=占位直接过；false=人工审核闸，停在 PENDING）。默认 false。 */
        private boolean autoPass = false;

        /**
         * 机审策略（可插拔开关）：决定激活哪个 {@link ContentModeration} 实现。
         * 默认 RULE（本地规则引擎，文件名/元数据级 fail-closed，非 AI 视觉）；
         * 视觉语义识别留 CLOUD 接入点（需用户提供云内容安全 API key 后接入）。
         * PASS=占位桩（恒 APPROVED）；AI=Ollama 本地视觉模型（已卸载，保留为接入点）。
         */
        private ModerationMode moderationMode = ModerationMode.RULE;

        /** 机审初筛违禁词表（RULE 引擎 fail-closed 命中即拦；与 CLOUD 视觉语义互补）。 */
        private List<String> bannedKeywords = List.of();

        /**
         * 机审置信度阈值（feature-match M1，moderation-design §P0-1）：机审裁定置信度低于该值，
         * 或显式要求人审（{@code needHumanScan}），则<b>强制送人审</b>（先审后放），不论账号信用等级。
         * 二值实现返回 {@code confidence=1.0} 不受影响；仅启用真实梯度机审（如云内容安全 API）后才生效。默认 0.9。
         */
        private double confidenceThreshold = 0.9;

        /**
         * 新人观察期解除阈值（{@code turbofeed.media.review.new-user-approve-threshold}）：
         * 新注册账号初始处于「先审后放」观察期，累计<b>人工审核通过</b>达到该帖数后自动转正常分级。
         * 只统计人工通过——先发后审的自动通过不计入，否则新号第一帖就会自己转正。
         */
        private int newUserApproveThreshold = 3;

        /** 加严队列持续天数（P0-5）：strict_queue_flag 超此窗口自动降级，避免永久加严。默认 30 */
        private int strictQueueWindowDays = 30;
        /** 新人观察期天数（P0-6）：new_user_watch 超此窗口即使人审通过数未达阈值也自动转正。默认 7 */
        private int newUserWatchWindowDays = 7;
        /** 信用分自然恢复步长（P0-5 增强）：每日凌晨给近 creditRecoverQuietDays 天无违规账号加分。默认 5 */
        private int creditRecoverStep = 5;
        /** 信用分自然恢复静默天数（P0-5 增强）：近 N 天有违规则不恢复。默认 30 */
        private int creditRecoverQuietDays = 30;

        /**
         * 临时封禁触发阈值（penalty 包骨架）：累计违规次数达到此值即临时封禁。默认 3
         * （骨架版按「累计总数」判断；抖音式“同类 N 次”需改按类目计数）。
         */
        private int banTempThreshold = 3;

        /** 临时封禁时长（天，penalty 包骨架）：到期后 {@code ban_until} 过期即自动恢复可写。默认 7 */
        private int banTempDays = 7;

        /**
         * 严重违规（{@code ViolationSeverity#CRITICAL}）是否直接<b>永久</b>封禁。
         *
         * <p><b>默认 false（灰度安全值）</b>：true 时一次 CRITICAL 判定即永久封号——不可逆，
         * 上线初期应先 false，让最重处置也只是临时封禁 {@code banTempDays} 天，
         * 观察误封率与申诉量稳定后再打开（配合 {@link PenaltyService#liftPenalty} 撤销通道）。</p>
         */
        private boolean criticalAutoPermBan = false;

        /**
         * 举报累计复审阈值（抖音式「举报累计 → 人工复核」，changelog 0065）：
         * 同一内容（media_id）的<b>待处理</b>举报数达到该值，且内容仍处于已发布态，
         * 自动建一条 PENDING 复审任务归 REVIEWER 二次研判。默认 3。
         *
         * <p>设计取舍：单条举报不立即拉人工（避免误举报刷爆审核台），累计达阈值才升级，
         * 与抖音「举报量触发人工复核」一致；阈值外部化，调参不发黑。</p>
         */
        private int reportReReviewThreshold = 3;

        /**
         * 热度阈值复审阈值（抖音式「越火审得越严」，changelog 0066）：
         * 同一内容的正向互动（点赞+评论+转发）经 Redis 累计达到该值，且内容仍处于已发布态，
         * 自动建一条 PENDING 复审任务（{@code HEAT_ACCUMULATED}）归 REVIEWER 二次研判。默认 1000。
         *
         * <p>设计取舍：与「举报累计」同构——单个赞/评论不立即拉人工，累计达阈值才升级，
         * 与抖音「越火的内容审得越严」一致；阈值外部化（{@code turbofeed.media.review.heat-re-review-threshold}），调参不发黑。</p>
         */
        private int heatReReviewThreshold = 1000;

        /**
         * 流量池分级热度复审阈值（抖音式「流量池分级」第3档，changelog 0067）：
         * 内容晋级到更高池后，其热度复审阈值按池级<b>收紧</b>——池越高、阈值越低（越火审得越严）。
         * 默认 L1=1000 / L2=500 / L3=200（与全局热度阈值 {@link #heatReReviewThreshold} 同量级、随池递减）。
         * 写入每帖覆盖键 {@code tf:media:heat-threshold:{postId}}，{@code MediaReviewService#onInteraction}
         * 累计热度时优先读该覆盖值，使更高池更快触发 {@code HEAT_ACCUMULATED} 复审。
         */
        private int poolHeatThresholdL1 = 1000;
        private int poolHeatThresholdL2 = 500;
        private int poolHeatThresholdL3 = 200;

        /**
         * 梯度处置矩阵（抖音式「梯度处置」，changelog 待补）：按 "来源:严重度" → 处置名
         * (INTERCEPT/MONITOR/REVIEW/PASS) 覆盖默认处置。缺省按内置默认
         * （HIGH/CRITICAL/MID→INTERCEPT，LOW→MONITOR）。
         * 例：{@code POOL_PROMOTED:HIGH=MONITOR} 把流量池晋级复扫命中改软处置（限公域而非下架）。
         */
        private Map<String, String> dispositionMatrix = new java.util.LinkedHashMap<>();

        /**
         * 账号健康分扣分点（P0-b）：确认一次违规按严重度扣的分。默认值对齐抖音式「先流量隔离」梯度。
         * 一处 CRITICAL(30) 即掉到降权区；两次 HIGH(20) 触限投稿；等。clamp 0~100。
         */
        private int healthDeductLow = 5;
        private int healthDeductMid = 10;
        private int healthDeductHigh = 20;
        private int healthDeductCritical = 30;

        /**
         * 举报人信用下限（P1 #131）：信用低于此值的举报人，其举报<b>不计入</b>复审升级
         * （即无法单独刷起人工复审台）。无信用记录的举报人按满分 100（无罪推定）处理。默认 50。
         */
        private int reporterCredibilityFloor = 50;

        /** 每举报人每小时最大举报数（P1 #131 恶意举报防御②：窗口频控，Redis 固定窗口 fail-open）。默认 30 */
        private int reporterRateLimitPerHour = 30;

        /** 举报频控窗口（秒，P1 #131）。默认 3600（1 小时）。 */
        private int reporterRateLimitWindowSeconds = 3600;

        /** 累计被驳回达此值后，每再被驳回一次额外扣健康分（P1 #131 恶意举报惩罚，复用 P0-b 健康分通道）。默认 5 */
        private int reporterAbuseRejectedThreshold = 5;

        /** 举报被确认成立时信用加分步长（P1 #131，clamp 0~100）。默认 5 */
        private int reporterCredibilityUpStep = 5;

        /** 举报被驳回时信用扣分步长（P1 #131，clamp 0~100）。默认 10 */
        private int reporterCredibilityDownStep = 10;

        /**
         * 举报水位窗口（秒，P1 #132 动态阈值）：近窗口举报总量计数键的过期时长。默认 3600（1 小时）。
         * 窗口内持续有举报则键续期，无举报后自然清零（水位回落）。
         */
        private int waterLevelWindowSeconds = 3600;

        /**
         * 举报水位「平静」上界（P1 #132）：近窗口举报总量 ≤ 此值视为平静 → 阈值放宽（乘数 1.5）。默认 50。
         */
        private int waterLevelCalm = 50;

        /**
         * 举报水位「繁忙」上界（P1 #132）：calm &lt; 总量 ≤ 此值视为正常（乘数 1.0）；
         * 再高至 surge 视为繁忙（收紧 0.7）。默认 200。
         */
        private int waterLevelBusy = 200;

        /**
         * 举报水位「峰涌」上界（P1 #132）：busy &lt; 总量 ≤ 此值视为繁忙（乘数 0.7）；
         * 再高视为峰涌（大幅收紧 0.4）。默认 500。
         */
        private int waterLevelSurge = 500;

        /**
         * 动态复审升级阈值下限（P1 #132）：clamp 下界，防水位峰涌时阈值压到 1 导致单举报即爆审核台。默认 2。
         */
        private int reportThresholdMin = 2;

        /**
         * 动态复审升级阈值上限（P1 #132）：clamp 上界，防水位长期平静时阈值无限放宽。默认 10。
         */
        private int reportThresholdMax = 10;

        public int getNewUserApproveThreshold() {
            return newUserApproveThreshold;
        }

        public void setNewUserApproveThreshold(int newUserApproveThreshold) {
            this.newUserApproveThreshold = newUserApproveThreshold;
        }

        public int getStrictQueueWindowDays() {
            return strictQueueWindowDays;
        }

        public void setStrictQueueWindowDays(int strictQueueWindowDays) {
            this.strictQueueWindowDays = strictQueueWindowDays;
        }

        public int getNewUserWatchWindowDays() {
            return newUserWatchWindowDays;
        }

        public void setNewUserWatchWindowDays(int newUserWatchWindowDays) {
            this.newUserWatchWindowDays = newUserWatchWindowDays;
        }

        public int getCreditRecoverStep() {
            return creditRecoverStep;
        }

        public void setCreditRecoverStep(int creditRecoverStep) {
            this.creditRecoverStep = creditRecoverStep;
        }

        public int getCreditRecoverQuietDays() {
            return creditRecoverQuietDays;
        }

        public void setCreditRecoverQuietDays(int creditRecoverQuietDays) {
            this.creditRecoverQuietDays = creditRecoverQuietDays;
        }

        public int getBanTempThreshold() {
            return banTempThreshold;
        }

        public void setBanTempThreshold(int banTempThreshold) {
            this.banTempThreshold = banTempThreshold;
        }

        public int getBanTempDays() {
            return banTempDays;
        }

        public void setBanTempDays(int banTempDays) {
            this.banTempDays = banTempDays;
        }

        public boolean isCriticalAutoPermBan() {
            return criticalAutoPermBan;
        }

        public void setCriticalAutoPermBan(boolean criticalAutoPermBan) {
            this.criticalAutoPermBan = criticalAutoPermBan;
        }

        public int getReportReReviewThreshold() {
            return reportReReviewThreshold;
        }

        public void setReportReReviewThreshold(int reportReReviewThreshold) {
            this.reportReReviewThreshold = reportReReviewThreshold;
        }

        public int getHeatReReviewThreshold() {
            return heatReReviewThreshold;
        }

        public void setHeatReReviewThreshold(int heatReReviewThreshold) {
            this.heatReReviewThreshold = heatReReviewThreshold;
        }

        public int getPoolHeatThresholdL1() {
            return poolHeatThresholdL1;
        }

        public void setPoolHeatThresholdL1(int poolHeatThresholdL1) {
            this.poolHeatThresholdL1 = poolHeatThresholdL1;
        }

        public int getPoolHeatThresholdL2() {
            return poolHeatThresholdL2;
        }

        public void setPoolHeatThresholdL2(int poolHeatThresholdL2) {
            this.poolHeatThresholdL2 = poolHeatThresholdL2;
        }

        public int getPoolHeatThresholdL3() {
            return poolHeatThresholdL3;
        }

        public void setPoolHeatThresholdL3(int poolHeatThresholdL3) {
            this.poolHeatThresholdL3 = poolHeatThresholdL3;
        }

        public Map<String, String> getDispositionMatrix() {
            return dispositionMatrix;
        }

        public void setDispositionMatrix(Map<String, String> dispositionMatrix) {
            this.dispositionMatrix = dispositionMatrix;
        }

        public int getHealthDeductLow() {
            return healthDeductLow;
        }

        public void setHealthDeductLow(int healthDeductLow) {
            this.healthDeductLow = healthDeductLow;
        }

        public int getHealthDeductMid() {
            return healthDeductMid;
        }

        public void setHealthDeductMid(int healthDeductMid) {
            this.healthDeductMid = healthDeductMid;
        }

        public int getHealthDeductHigh() {
            return healthDeductHigh;
        }

        public void setHealthDeductHigh(int healthDeductHigh) {
            this.healthDeductHigh = healthDeductHigh;
        }

        public int getHealthDeductCritical() {
            return healthDeductCritical;
        }

        public void setHealthDeductCritical(int healthDeductCritical) {
            this.healthDeductCritical = healthDeductCritical;
        }

        public int getReporterCredibilityFloor() {
            return reporterCredibilityFloor;
        }

        public void setReporterCredibilityFloor(int reporterCredibilityFloor) {
            this.reporterCredibilityFloor = reporterCredibilityFloor;
        }

        public int getReporterRateLimitPerHour() {
            return reporterRateLimitPerHour;
        }

        public void setReporterRateLimitPerHour(int reporterRateLimitPerHour) {
            this.reporterRateLimitPerHour = reporterRateLimitPerHour;
        }

        public int getReporterRateLimitWindowSeconds() {
            return reporterRateLimitWindowSeconds;
        }

        public void setReporterRateLimitWindowSeconds(int reporterRateLimitWindowSeconds) {
            this.reporterRateLimitWindowSeconds = reporterRateLimitWindowSeconds;
        }

        public int getReporterAbuseRejectedThreshold() {
            return reporterAbuseRejectedThreshold;
        }

        public void setReporterAbuseRejectedThreshold(int reporterAbuseRejectedThreshold) {
            this.reporterAbuseRejectedThreshold = reporterAbuseRejectedThreshold;
        }

        public int getReporterCredibilityUpStep() {
            return reporterCredibilityUpStep;
        }

        public void setReporterCredibilityUpStep(int reporterCredibilityUpStep) {
            this.reporterCredibilityUpStep = reporterCredibilityUpStep;
        }

        public int getReporterCredibilityDownStep() {
            return reporterCredibilityDownStep;
        }

        public void setReporterCredibilityDownStep(int reporterCredibilityDownStep) {
            this.reporterCredibilityDownStep = reporterCredibilityDownStep;
        }

        public int getWaterLevelWindowSeconds() {
            return waterLevelWindowSeconds;
        }

        public void setWaterLevelWindowSeconds(int waterLevelWindowSeconds) {
            this.waterLevelWindowSeconds = waterLevelWindowSeconds;
        }

        public int getWaterLevelCalm() {
            return waterLevelCalm;
        }

        public void setWaterLevelCalm(int waterLevelCalm) {
            this.waterLevelCalm = waterLevelCalm;
        }

        public int getWaterLevelBusy() {
            return waterLevelBusy;
        }

        public void setWaterLevelBusy(int waterLevelBusy) {
            this.waterLevelBusy = waterLevelBusy;
        }

        public int getWaterLevelSurge() {
            return waterLevelSurge;
        }

        public void setWaterLevelSurge(int waterLevelSurge) {
            this.waterLevelSurge = waterLevelSurge;
        }

        public int getReportThresholdMin() {
            return reportThresholdMin;
        }

        public void setReportThresholdMin(int reportThresholdMin) {
            this.reportThresholdMin = reportThresholdMin;
        }

        public int getReportThresholdMax() {
            return reportThresholdMax;
        }

        public void setReportThresholdMax(int reportThresholdMax) {
            this.reportThresholdMax = reportThresholdMax;
        }

        public boolean isAutoPass() {
            return autoPass;
        }

        public void setAutoPass(boolean autoPass) {
            this.autoPass = autoPass;
        }

        public ModerationMode getModerationMode() {
            return moderationMode;
        }

        public void setModerationMode(ModerationMode moderationMode) {
            this.moderationMode = moderationMode;
        }

        public List<String> getBannedKeywords() {
            return bannedKeywords;
        }

        public void setBannedKeywords(List<String> bannedKeywords) {
            this.bannedKeywords = bannedKeywords;
        }

        public double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }
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
        private String namespace = "tf:mod:";

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
}
