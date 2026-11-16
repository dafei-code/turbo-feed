package com.turbofeed.gateway.service.moderation;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 词库 / 白名单的<b>跨实例版本 epoch 与失效广播</b>（阶段 B2）。
 *
 * <h3>它解决什么</h3>
 * <p>改造前两张表都是「每实例一份 {@code AtomicReference} 不可变快照」：admin 写 DB 后
 * <b>只有收到请求的那一个实例</b>重建，其它实例只能等各自的 30s
 * {@code (COUNT, MAX(updated_at))} 指纹定时器碰巧发现变化。多实例下新词 / 新豁免的
 * 生效时延上界是 30s，且没有任何手段让运维「立刻全量生效」。</p>
 *
 * <h3>方案</h3>
 * <ul>
 *   <li><b>全局单调 epoch</b>：Redis 单 Key {@code INCR}，只在数据变更后推进一次。
 *       epoch 不设 TTL（参考 {@code RecommendedFeedService} 的全局版本号），
 *       每次写操作一个整数，与行级 {@code revision}（每行独立自增、仅审计）无关。</li>
 *   <li><b>广播</b>：pub/sub 发 {@code instanceId:kind:epoch}。接收方只接受
 *       <b>严格更大</b>的 epoch——晚到、乱序、重复、自广播一律忽略，绝不让快照回退。</li>
 *   <li><b>兜底</b>：pub/sub 不保证送达，且实例可能在广播时还没启动。每 30s 再读一次
 *       epoch Key，本地落后就补一次重建。广播负责「快」，轮询负责「一定到」。</li>
 *   <li><b>双保险</b>：Service 原有的 {@code CheckSum} 定时刷新<b>完全保留</b>。
 *       Redis 整体不可用、epoch Key 被误删、广播与轮询都失败时，行为退化成改造前的
 *       30s 指纹刷新——这是本方案的下界，不是附加条件。</li>
 * </ul>
 *
 * <h3>为什么不用 {@code CheckSum} 当 epoch</h3>
 * <p>{@code (COUNT, MAX(updated_at))} 不是单调的：一增一删 count 回退，同一秒内的
 * UPDATE 推进不了 MAX，跨实例拿它比较大小会得出错误结论。行级 {@code revision} 同理，
 * 它每行独立 +1，没有全局序。所以必须另立一个原子自增的全局序号。</p>
 *
 * <h3>失败方向</h3>
 * <p>全链路 fail-open：Redis 连不上、INCR 失败、序列化失败、订阅回调异常，都只记日志，
 * <b>绝不阻断 admin 写请求，也绝不清空本地快照</b>。旧词库 / 旧豁免集合总比空集合安全。</p>
 *
 * <h3>范围纪律</h3>
 * <p>epoch Key 与频道只在网关内部使用（发布与订阅都在同一模块），因此不登记进
 * {@code tf-shared/RedisKeys}——那个门面只放跨服务共享的 key。频道命名沿用
 * {@code turbofeed:*:invalidate} 既有风格。</p>
 */
@Component
public class ModerationEpochBroker {

    private static final Logger log = LoggerFactory.getLogger(ModerationEpochBroker.class);

    /** 词库 / 白名单共用一个频道，用消息体里的 kind 区分，避免两个订阅容器各自重试。 */
    public static final String CHANNEL = "turbofeed:moderation:dictionary:invalidate";

    public static final String DICT_EPOCH_KEY = "tf:mod:dict:epoch";
    public static final String WHITELIST_EPOCH_KEY = "tf:mod:whitelist:epoch";

    /** 两类快照；各自持有独立的 epoch Key，互不阻塞（改词库不该让白名单全量重建）。 */
    public enum Kind {
        DICT(DICT_EPOCH_KEY),
        WHITELIST(WHITELIST_EPOCH_KEY);

        private final String epochKey;

        Kind(String epochKey) {
            this.epochKey = epochKey;
        }

        public String epochKey() {
            return epochKey;
        }
    }

    /**
     * Redis 操作抽象：让 broker 的版本判定逻辑可以脱离 Spring / Redis 做单测。
     * 所有方法都允许抛异常，由 broker 统一兜住并退化。
     */
    public interface Transport {
        long increment(String key);

        long get(String key);

        void publish(String channel, String payload);
    }

    private final Transport transport;
    private final boolean enabled;
    private final String instanceId;
    private final Map<Kind, AtomicLong> seenEpochs = new EnumMap<>(Kind.class);
    private final Map<Kind, Runnable> triggers = new ConcurrentHashMap<>();

    @Autowired
    public ModerationEpochBroker(StringRedisTemplate redisTemplate,
                                 @Value("${turbofeed.content-security.epoch-broadcast-enabled:false}") boolean enabled) {
        this(new RedisTransport(redisTemplate), enabled, UUID.randomUUID().toString());
    }

    /** 单测用：注入假 Transport 与固定 instanceId，不触碰 Redis。 */
    ModerationEpochBroker(Transport transport, boolean enabled, String instanceId) {
        this.transport = transport;
        this.enabled = enabled;
        this.instanceId = instanceId;
        for (Kind kind : Kind.values()) {
            seenEpochs.put(kind, new AtomicLong(0L));
        }
    }

    @PostConstruct
    void logStartupState() {
        if (enabled) {
            log.info("词库/白名单跨实例 epoch 广播已启用: channel={}, instanceId={}", CHANNEL, instanceId);
        } else {
            log.info("词库/白名单跨实例 epoch 广播未启用（默认关闭，退化为各实例 30s 指纹刷新）");
        }
    }

    /** 注册「收到失效通知后该让谁重建」，由 {@code ModerationEpochConfig} 在启动时接线。 */
    public void registerTrigger(Kind kind, Runnable trigger) {
        triggers.put(kind, trigger);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String instanceId() {
        return instanceId;
    }

    /** 本实例已见过的最大 epoch（排障 / 单测用）。 */
    public long currentEpoch(Kind kind) {
        return seenEpochs.get(kind).get();
    }

    /**
     * 启动时把本地基线对齐到全局 epoch，避免「刚启动就因历史 epoch 白白重建一次」。
     * 启动时 {@code init()} 已从 DB 同步加载过最新快照，因此对齐基线而不触发重建是安全的。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void alignBaselineOnStartup() {
        if (!enabled) {
            return;
        }
        for (Kind kind : Kind.values()) {
            try {
                long remote = transport.get(kind.epochKey());
                if (remote > 0) {
                    advance(kind, remote, true);
                }
            } catch (Exception e) {
                log.debug("启动期读取 {} epoch 失败（不影响启动，交给定时兜底）: {}", kind, e.toString());
            }
        }
    }

    /**
     * admin 数据写完后调用：推进全局 epoch 并广播。返回新 epoch；失败返回 {@code -1}。
     *
     * <p><b>调用位置必须在 DB 写入之后</b>（Controller 层），否则接收方可能读到未提交的数据。
     * INCR 成功但 publish 失败时仍返回新 epoch：其它实例会由 30s epoch 兜底补齐。</p>
     */
    public long bump(Kind kind) {
        if (!enabled) {
            return -1L;
        }
        try {
            long epoch = transport.increment(kind.epochKey());
            if (epoch <= 0) {
                log.warn("词库 epoch 推进返回非法值，跳过广播: kind={}, value={}", kind, epoch);
                return -1L;
            }
            // Redis 数据被清空后 INCR 会从 1 重新开始；允许发布方把本地基线跟随回退，
            // 否则后续广播永远被自己当成「旧消息」忽略。
            advance(kind, epoch, true);
            try {
                transport.publish(CHANNEL, instanceId + ":" + kind.name() + ":" + epoch);
            } catch (Exception e) {
                log.warn("词库 epoch 广播发送失败（版本已推进，由 30s epoch 兜底补齐）: kind={}, epoch={}, {}",
                        kind, epoch, e.toString());
            }
            return epoch;
        } catch (Exception e) {
            log.warn("词库 epoch 推进失败（退化为各实例 30s 指纹刷新）: kind={}, {}", kind, e.toString());
            return -1L;
        }
    }

    /**
     * Redis 订阅回调：解析 {@code instanceId:kind:epoch}，只接受严格更大的 epoch。
     *
     * <p>自广播必须跳过——发布方自己已经重建过，否则多实例之间会形成回环。
     * 回调未注册时<b>刻意不推进本地基线</b>，让 30s 兜底在注册完成后补上，避免吞掉消息。</p>
     */
    public void onRemoteMessage(String body) {
        if (!enabled || body == null || body.isBlank()) {
            return;
        }
        String[] parts = body.split(":", 3);
        if (parts.length != 3) {
            log.warn("词库失效广播格式非法，已忽略: {}", body);
            return;
        }
        if (instanceId.equals(parts[0])) {
            return;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            log.warn("词库失效广播 kind 非法，已忽略: {}", parts[1]);
            return;
        }
        long epoch;
        try {
            epoch = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            log.warn("词库失效广播 epoch 非法，已忽略: {}", parts[2]);
            return;
        }
        if (epoch <= 0) {
            return;
        }
        Runnable trigger = triggers.get(kind);
        if (trigger == null) {
            log.warn("收到 {} 失效广播但重建回调未注册，保留落后状态等待兜底: epoch={}", kind, epoch);
            return;
        }
        if (!advance(kind, epoch, false)) {
            return; // 重复 / 乱序 / 旧消息：绝不触发重建，也绝不让快照回退
        }
        try {
            trigger.run();
        } catch (Exception e) {
            log.warn("词库失效广播触发重建失败（保留旧快照）: kind={}, epoch={}, {}", kind, epoch, e.toString());
        }
    }

    /**
     * 30s 兜底：读全局 epoch，本地落后就补一次重建；全局 epoch 回退（Redis 被重置）时
     * 把本地基线跟随下来，使后续新广播重新可生效。
     *
     * <p>与 Service 自己的 {@code CheckSum} 定时器是两套独立信号：这里补的是
     * 「pub/sub 丢消息 / 实例启动错过消息」，那边补的是「Redis 完全不可用」。</p>
     *
     * @return 是否触发了一次重建
     */
    public boolean ensureFresh(Kind kind) {
        if (!enabled) {
            return false;
        }
        Runnable trigger = triggers.get(kind);
        if (trigger == null) {
            return false;
        }
        try {
            long remote = transport.get(kind.epochKey());
            if (remote <= 0) {
                return false;
            }
            if (!advance(kind, remote, true)) {
                return false;
            }
            trigger.run();
            log.info("词库 epoch 兜底触发重建: kind={}, epoch={}", kind, remote);
            return true;
        } catch (Exception e) {
            log.warn("词库 epoch 兜底检查失败（退化为 30s 指纹刷新）: kind={}, {}", kind, e.toString());
            return false;
        }
    }

    /**
     * 原子推进本地已见 epoch。
     *
     * @param allowRollback 是否允许在「全局 epoch 变小」时跟随回退（发布方 / 定时兜底允许，
     *                      接收广播时不允许——防止一条迟到的旧消息把基线拉回去）
     * @return true 表示确实推进到了一个更大的值（调用方应触发重建）
     */
    private boolean advance(Kind kind, long epoch, boolean allowRollback) {
        AtomicLong ref = seenEpochs.get(kind);
        while (true) {
            long cur = ref.get();
            if (epoch == cur) {
                return false;
            }
            if (epoch < cur) {
                if (allowRollback && ref.compareAndSet(cur, epoch)) {
                    log.warn("检测到全局 epoch 回退（Redis 数据被重置？），本地基线已跟随: kind={}, {} -> {}",
                            kind, cur, epoch);
                }
                return false;
            }
            if (ref.compareAndSet(cur, epoch)) {
                return true;
            }
        }
    }

    /** 默认 Transport：只依赖 {@code StringRedisTemplate}，不需要 Redisson / RTopic。 */
    private static final class RedisTransport implements Transport {

        private final StringRedisTemplate template;

        private RedisTransport(StringRedisTemplate template) {
            this.template = template;
        }

        @Override
        public long increment(String key) {
            Long value = template.opsForValue().increment(key);
            return value == null ? -1L : value;
        }

        @Override
        public long get(String key) {
            String value = template.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return -1L;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        @Override
        public void publish(String channel, String payload) {
            template.convertAndSend(channel, payload);
        }
    }
}
