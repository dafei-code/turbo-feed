package com.turbofeed.shared.feature;

/**
 * 特性开关属性键集中定义（P2 配置治理）。
 *
 * <p>所有 {@code @ConditionalOnProperty} / {@code @Value} 对开关属性的引用统一走本类常量——
 * <b>属性名拼错即编译期报错</b>，从根上消除 CONTRIBUTING.md 记录的
 * "{@code @ConditionalOnProperty} 字面量拼错 → 不报错、开关不动"静默 no-op 事故
 * （历史实例：{@code turbofeed.counter.redis.enabled} vs {@code redis-enabled}）。</p>
 *
 * <p><b>框架无关</b>：本类无任何 Spring 依赖，仅承载字符串常量，可被所有模块（含 tf-shared 自身）安全引用。</p>
 *
 * <p>与 {@link FeatureFlagGuard} 共用同一套键；新增开关时请在此登记，勿在业务代码里写裸字符串。</p>
 */
public final class FeatureFlagKeys {

    private FeatureFlagKeys() {
    }

    // ===== RocketMQ 事件总线 / 时间线投递（跨网关/引擎/计数三模块）=====
    public static final String MQ_ENABLED = "turbofeed.mq.enabled";
    /** RocketMQ starter 自带属性（无 turbofeed 前缀），缺省即 starter 自动配置不生效。 */
    public static final String ROCKETMQ_NAME_SERVER = "rocketmq.name-server";

    // ===== PR-D3 时间线墓碑防旧（拦截 DEAD/DLQ 重投的陈旧 APPEND）=====
    public static final String FEED_TIMELINE_TOMBSTONE_ENABLED = "turbofeed.feed.timeline.tombstone-enabled";

    // ===== 死信 replay（依赖 MQ）=====
    public static final String FEED_TIMELINE_DLQ_REPLAY_ENABLED = "turbofeed.feed.timeline.dlq-replay.enabled";

    // ===== 死信 replay 积压告警（依赖 DLQ replay + MQ）=====
    public static final String FEED_TIMELINE_DLQ_ALERT_ENABLED = "turbofeed.feed.timeline.dlq-alert.enabled";

    // ===== 向量召回 =====
    public static final String VECTOR_MODE = "turbofeed.feed.recall.vector.mode";
    public static final String VECTOR_TWO_TOWER_ENABLED = "turbofeed.feed.recall.vector.two-tower.enabled";

    // ===== Redis 拓扑（single / cluster / sentinel）=====
    public static final String REDIS_MODE = "turbofeed.redis.mode";
    public static final String REDIS_REDISSON_ENABLED = "turbofeed.redis.redisson-enabled";
    public static final String REDIS_CLUSTER_NODES = "turbofeed.redis.cluster-nodes";
    public static final String REDIS_SENTINEL_MASTER = "turbofeed.redis.sentinel-master";
    public static final String REDIS_SENTINEL_NODES = "turbofeed.redis.sentinel-nodes";
    public static final String REDIS_MAX_REDIRECTS = "turbofeed.redis.max-redirects";

    // ===== 计数服务 =====
    public static final String COUNTER_REDIS_ENABLED = "turbofeed.counter.redis-enabled";
    public static final String COUNTER_REDIS_BUFFER_ENABLED = "turbofeed.counter.redis-buffer-enabled";
    public static final String COUNTER_SERVICE_ENABLED = "turbofeed.counter.service.enabled";
    public static final String COUNTER_SERVICE_TOPIC = "turbofeed.counter.service.topic";

    // ===== 热点帖计数 Key 分槽（评审优化项③，默认关闭；开启后读放大 64 倍）=====
    public static final String LIKE_COUNT_SHARDING_ENABLED = "turbofeed.like.count-sharding-enabled";

    // ===== 评论列表页短 TTL 缓存（评审优化项④，默认关闭；落库成功后按 generation 失效）=====
    public static final String COMMENT_PAGE_CACHE_ENABLED = "turbofeed.comment.page-cache-enabled";

    // ===== 媒资存储（minio / local / placeholder）=====
    public static final String MEDIA_STORAGE = "turbofeed.media.storage";

    // ===== 短链分享 =====
    /** 分享短码混淆密钥（生产必须显式配置，默认串仅供本地演示）。 */
    public static final String SHARE_CODE_SECRET = "turbofeed.share.code-secret";
    /** 短链对外基址（为空时返回相对路径 /s/{code}，由前端拼域名）。 */
    public static final String SHARE_BASE_URL = "turbofeed.share.base-url";
    /** 短链跳转成功后的前端落地页地址（前端由独立静态服务托管，不在网关静态目录）。 */
    public static final String SHARE_FRONTEND_URL = "turbofeed.share.frontend-url";

    // ===== 缓存失效广播 =====
    public static final String STATE_CACHE_BROADCAST_ENABLED = "turbofeed.state-cache.broadcast-enabled";

    // ===== 内容安全词库 / 白名单跨实例 epoch 广播（阶段 B2，默认关闭）=====
    public static final String MODERATION_EPOCH_BROADCAST_ENABLED = "turbofeed.content-security.epoch-broadcast-enabled";

    // ===== Snowflake ID 生成器（lease / 单机）=====
    public static final String SNOWFLAKE_LEASE_ENABLED = "turbofeed.snowflake.lease-enabled";

    // ===== Sentinel / Nacos 规则热推 =====
    public static final String SENTINEL_NACOS_ENABLED = "turbofeed.sentinel.nacos.enabled";

    // ===== 发件箱补偿 / 指标 =====
    public static final String OUTBOX_RELAY_ENABLED = "turbofeed.outbox.relay-enabled";
    public static final String OUTBOX_BACKLOG_METRICS_ENABLED = "turbofeed.outbox.backlog-metrics-enabled";

    // ===== 发件箱 PR-B/PR-D2/PR-D5：上传事件灰度 + 传输通道收口（默认关闭 / 留空跟随总闸）=====
    public static final String OUTBOX_MEDIA_UPLOADED_ENABLED = "turbofeed.outbox.media-uploaded.enabled";
    public static final String OUTBOX_CHANNEL_DEFAULT = "turbofeed.outbox.channel.default";
    public static final String OUTBOX_CHANNEL_MEDIA_UPLOADED = "turbofeed.outbox.channel.media-uploaded";

    // ===== 搜索离线重建数据源 =====
    public static final String SEARCH_REBUILD_OFFLINE_DS0_URL = "turbofeed.search.rebuild.offline.ds0-url";
}
