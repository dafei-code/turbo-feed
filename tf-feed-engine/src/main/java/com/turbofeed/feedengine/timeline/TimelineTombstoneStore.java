package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 时间线墓碑（PR-D3）：记录「某帖最后一次被下架」的时刻，拦截之后迟到的 APPEND。
 *
 * <h3>要挡住的事故</h3>
 * <p>{@code TIMELINE_APPEND} 的发件箱事件行可能判死（DEAD），之后同一帖又被
 * {@code TIMELINE_REMOVE} 下架。{@code OutboxDeadLetterSweeper} 重投那条 DEAD APPEND 时，
 * 引擎若无脑写入，<b>已下架内容会在公域复活</b>——这是内容安全事故，不是数据重复。
 * 顺序消息只能保证同一队列内按序，挡不住「跨队列 / 跨通道 / 事隔数小时」的重投。</p>
 *
 * <h3>判定口径</h3>
 * <p>每个事件带一个 {@code occurredAt}（网关 enqueue 时刻，不是投递时刻）。REMOVE 落地时
 * 把 {@code tf:timeline:tombstone:{timelineKey}} 写成该时刻；APPEND 落地前若
 * {@code append.occurredAt <= tombstone}，说明这条 APPEND 产生于下架之前，判定为陈旧并拒绝。
 * 反过来 {@code append.occurredAt > tombstone} 是<b>合法</b>的：申诉翻案、重新过审都会
 * 产生更晚的 APPEND，必须能重新入流，不能一刀切永久封杀。</p>
 *
 * <h3>为什么在引擎侧</h3>
 * <p>复活发生在引擎持有的时间线 ZSET 里，判定点必须离写最近、且必须是<b>所有通道的唯一入口</b>
 * （HTTP 兜底 / RocketMQ 顺序消息 / 引擎 DLQ 重放都汇到 {@link FeedTimelineDeliveryService}）。
 * 网关 Relay 不知道引擎当前的下架状态，也无法覆盖引擎自己的 DLQ 重放。
 * 注意评审文档把落点写成「引擎侧 {@code FeedTimelinePublisher.remove}」不准确：
 * 那是网关侧端口，引擎侧落点是 {@link FeedTimelineStore#removeStrict} 的调用方。</p>
 *
 * <h3>写入只前进不回退</h3>
 * <p>下架事件本身也会被重放（DLQ / Sweeper），乱序到达时旧 REMOVE 不能把墓碑水位改小，
 * 否则本该拦住的 APPEND 会放行。故用一段 Lua 做「仅当候选时刻更大才 SET」的原子比较写入。
 * 值存 epoch 毫秒字符串，便于 Lua 内 {@code tonumber} 比较，也避免跨语言时间格式解析。</p>
 *
 * <h3>失败语义</h3>
 * <p>Redis 不可用时<b>一律放行</b>：时间线本来就依赖同一个 Redis，读不到墓碑时即使放行也写不进去；
 * 反过来若因读失败就拒绝所有 APPEND，等于 Redis 抖动期间全站停发，代价远大于防线本身。
 * 异常只记 WARN，指标另计，绝不抛给投递链路。</p>
 *
 * <h3>存量兼容</h3>
 * <p>{@code occurredAt == null}（存量事件行 / 存量 MQ 消息 / 未接防旧的调用点）一律放行，
 * REMOVE 也不写墓碑。防旧只对<b>带时刻的新生效事件</b>启用，存量行为零变化。</p>
 */
@Slf4j
@Component
public class TimelineTombstoneStore {

    /** 墓碑键前缀；与 APPEND / REMOVE 用同一个 timelineKey，口径见 FeedItemView#timelineKey()。 */
    static final String KEY_PREFIX = "tf:timeline:tombstone:";

    /**
     * KEYS[1]=墓碑键, ARGV[1]=候选 epoch 毫秒, ARGV[2]=TTL 毫秒。
     * 返回 1 = 本次写入生效，0 = 已有更晚的墓碑（保持原值）。
     */
    static final String LUA_ADVANCE = """
            local cur = redis.call('GET', KEYS[1])
            if (not cur) or (tonumber(ARGV[1]) > tonumber(cur)) then
              redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
              return 1
            end
            return 0
            """;

    private static final RedisScript<Long> ADVANCE_SCRIPT = RedisScript.of(LUA_ADVANCE, Long.class);

    /** ADR §5 指定的指标名（与本类其余 tf_ 前缀指标命名不同，按评审文档保留）。 */
    static final String METRIC_STALE_BLOCKED = "feed_timeline_stale_append_blocked_total";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final long ttlMillis;

    public TimelineTombstoneStore(StringRedisTemplate redisTemplate,
                                  MeterRegistry meterRegistry,
                                  @Value("${" + FeatureFlagKeys.FEED_TIMELINE_TOMBSTONE_ENABLED + ":true}") boolean enabled,
                                  @Value("${turbofeed.feed.timeline.tombstone-ttl-days:30}") long ttlDays) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        // 非法 / 非正值回落到 30 天，避免 TTL=0 让墓碑瞬间失效（等于没防）
        this.ttlMillis = ttlDays > 0 ? ttlDays * 24L * 60L * 60L * 1000L : 30L * 24L * 60L * 60L * 1000L;
    }

    /**
     * APPEND 是否陈旧（应被拒绝投递）。
     *
     * <p>返回 {@code true} 时调用方必须<b>直接结束且不抛异常</b>——抛异常会触发
     * 发件箱重试 / RocketMQ DLQ，而「永远不该赢」的事件重试多少次都是同一个结论。
     * 不抛异常才能让事件正常标 SENT / ACK，这正是 ADR 要求的终态。</p>
     *
     * @param timelineKey 帖身份
     * @param occurredAt  事件产生时刻；{@code null} = 存量事件，放行
     */
    public boolean isStaleAppend(String timelineKey, Instant occurredAt) {
        if (!enabled || occurredAt == null || timelineKey == null || timelineKey.isBlank()) {
            return false;
        }
        try {
            String raw = redisTemplate.opsForValue().get(KEY_PREFIX + timelineKey);
            if (raw == null || raw.isBlank()) {
                return false;
            }
            long tombstone = Long.parseLong(raw.trim());
            if (occurredAt.toEpochMilli() <= tombstone) {
                Counter.builder(METRIC_STALE_BLOCKED)
                        .description("被时间线墓碑拦截的陈旧 APPEND 累计数（防止已下架内容复活）")
                        .register(meterRegistry)
                        .increment();
                log.warn("陈旧 APPEND 已被墓碑拒绝（不写入时间线，事件按成功终态结束）: "
                                + "timelineKey={}, appendOccurredAt={}, tombstone={}",
                        timelineKey, occurredAt, Instant.ofEpochMilli(tombstone));
                return true;
            }
        } catch (NumberFormatException e) {
            // 脏数据（人工改过键值）：按「无有效墓碑」放行，并留痕供排查
            log.warn("时间线墓碑值非法，按无墓碑放行: timelineKey={}, {}", timelineKey, e.getMessage());
        } catch (Exception e) {
            // fail-open：见类注释「失败语义」
            log.warn("时间线墓碑读取失败（fail-open，按无墓碑放行）: timelineKey={}, {}",
                    timelineKey, e.getMessage());
        }
        return false;
    }

    /**
     * REMOVE 落地后登记墓碑。{@code occurredAt == null}（存量事件）时不写，保持历史行为。
     * 失败只告警：墓碑是防线，绝不能让下架主流程因它失败而回滚。
     */
    public void markRemoved(String timelineKey, Instant occurredAt) {
        if (!enabled || occurredAt == null || timelineKey == null || timelineKey.isBlank()) {
            return;
        }
        try {
            Long changed = redisTemplate.execute(ADVANCE_SCRIPT,
                    List.of(KEY_PREFIX + timelineKey),
                    String.valueOf(occurredAt.toEpochMilli()), String.valueOf(ttlMillis));
            if (changed != null && changed == 0L) {
                log.debug("已有更晚的时间线墓碑，保持原值: timelineKey={}, candidate={}",
                        timelineKey, occurredAt);
            }
        } catch (Exception e) {
            log.warn("时间线墓碑写入失败（不影响下架主流程）: timelineKey={}, {}",
                    timelineKey, e.getMessage());
        }
    }

    /** 开关状态（供运维自检 / 单测断言，不暴露内部键）。 */
    public boolean isEnabled() {
        return enabled;
    }
}
