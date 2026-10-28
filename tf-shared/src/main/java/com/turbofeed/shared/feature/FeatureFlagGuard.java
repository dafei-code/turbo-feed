package com.turbofeed.shared.feature;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 特性开关启动期校验（P2 配置治理第一刀）。
 *
 * <p>本项目大量使用 {@code @ConditionalOnProperty} 做互斥装配，但其按字面量查 {@code Environment}、
 * <b>不做 relaxed binding</b>——属性名拼错 / 值非法时<b>不报错、开关不动</b>，是典型的"静默 no-op"事故源
 * （见 CONTRIBUTING.md 与 docs 多处记录：{@code turbofeed.counter.redis.enabled} vs {@code redis-enabled}、
 * {@code rocketmq.name-server} 无 havingValue 等）。本类把这些"危险组合"在启动早期集中 fail-fast，
 * 给出可读的中文报错，而不是让应用在缺 Bean / 错模式下以诡异方式崩溃或静默降级。</p>
 *
 * <p><b>框架无关</b>：本类不依赖 Spring，由各模块用 {@code Environment} 适配 {@link PropertyLookup} 后调用，
 * 便于单测与跨模块复用。校验逻辑纯函数，不读网络、不碰中间件。</p>
 *
 * <p><b>触发时机</b>：由各模块以 {@code static @Bean BeanFactoryPostProcessor} 在上下文 refresh 早期、
 * 单例实例化之前调用，从而抢在 RocketMQ / Redisson 等 starter 的"缺 Bean 静默失败"之前报错。</p>
 *
 * <p><b>默认配置零影响</b>：{@code mq=false / redis.mode=single / counter 全关 / two-tower=false} 时所有检查均不触发。</p>
 */
public final class FeatureFlagGuard {

    /** 属性读取适配器（各模块注入 Spring {@code Environment}）。 */
    @FunctionalInterface
    public interface PropertyLookup {
        /** 返回属性原始值，缺省返回 {@code null}。 */
        String get(String key);
    }

    private final PropertyLookup lookup;
    private final Set<String> activeProfiles;

    public FeatureFlagGuard(PropertyLookup lookup, String[] activeProfiles) {
        this.lookup = lookup;
        this.activeProfiles = new HashSet<>(Arrays.asList(activeProfiles));
    }

    /** 执行全部校验，命中危险组合直接抛 {@link IllegalStateException}（信息可读、可操作）。 */
    public void validate() {
        validateMessaging();
        validateDlqReplay();
        validateRedisTopology();
        validateCounterBuffer();
        validateVectorRecall();
        validateMediaStorage();
    }

    // ===== 各命名空间校验 =====

    /** MQ 总闸：开启但无 name-server 或未激活 mq profile → 明确失败（否则缺 RocketMQTemplate/消费者 Bean 静默崩）。 */
    private void validateMessaging() {
        if (!bool("turbofeed.mq.enabled")) {
            return;
        }
        boolean hasNameServer = hasText(prop("rocketmq.name-server"));
        boolean mqProfile = activeProfiles.contains("mq");
        if (!hasNameServer || !mqProfile) {
            StringBuilder sb = new StringBuilder("turbofeed.mq.enabled=true 但 RocketMQ 连接未就绪：");
            if (!hasNameServer) {
                sb.append(" 缺少 rocketmq.name-server；");
            }
            if (!mqProfile) {
                sb.append(" 未激活 mq profile（需 --spring.profiles.active=mq 加载 application-mq.yml 的连接配置）；");
            }
            sb.append(" 二者必须同时具备，否则启动会因缺少 RocketMQTemplate / 消费者 Bean 失败。"
                    + " 若仅需本地运行，请将 turbofeed.mq.enabled 置 false（默认）走 HTTP 兜底。");
            fail(sb.toString());
        }
    }

    /** 死信 replay 依赖 MQ：开 dlq-replay 但没开 mq → 死信链路根本不工作。 */
    private void validateDlqReplay() {
        if (bool("turbofeed.feed.timeline.dlq-replay.enabled") && !bool("turbofeed.mq.enabled")) {
            fail("turbofeed.feed.timeline.dlq-replay.enabled=true 依赖 turbofeed.mq.enabled=true，"
                    + " 但 MQ 未启用，死信 replay 无法工作。请同时开启 turbofeed.mq.enabled 并激活 mq profile。");
        }
    }

    /** Redis 拓扑：cluster / sentinel 必须有对应节点或 master，否则 Redisson/Lettuce 启动即连不上。 */
    private void validateRedisTopology() {
        String mode = prop("turbofeed.redis.mode");
        if (mode == null) {
            mode = "single"; // 与 @ConditionalOnProperty(matchIfMissing=true) 默认一致
        }
        switch (mode) {
            case "cluster":
                if (!hasText(prop("turbofeed.redis.cluster.nodes"))) {
                    fail("turbofeed.redis.mode=cluster 必须配置 turbofeed.redis.cluster.nodes（逗号分隔的 host:port 列表）。");
                }
                break;
            case "sentinel":
                if (!hasText(prop("turbofeed.redis.sentinel-master"))) {
                    fail("turbofeed.redis.mode=sentinel 必须配置 turbofeed.redis.sentinel-master。");
                }
                break;
            case "single":
                break;
            default:
                fail("turbofeed.redis.mode 取值非法：" + mode + "（允许 single / cluster / sentinel）。");
        }
    }

    /** 计数缓冲：redis-buffer 依赖 redis-enabled（需 RedissonClient），只开缓冲不开 Redis 会 NoSuchBean。 */
    private void validateCounterBuffer() {
        boolean buffer = bool("turbofeed.counter.redis-buffer-enabled");
        boolean redis = bool("turbofeed.counter.redis-enabled");
        if (buffer && !redis) {
            fail("turbofeed.counter.redis-buffer-enabled=true 需同时 turbofeed.counter.redis-enabled=true"
                    + "（缓冲依赖 RedissonClient）。或关闭 redis-buffer-enabled 退回进程内缓冲。");
        }
    }

    /** 向量召回：two-tower 训练需 mode=model/ann；mode 非法值会被 TagVectorRecallChannel 静默走默认分支。 */
    private void validateVectorRecall() {
        String mode = prop("turbofeed.feed.recall.vector.mode");
        if (mode != null && !Set.of("hash", "model", "ann").contains(mode)) {
            fail("turbofeed.feed.recall.vector.mode 取值非法：" + mode + "（允许 hash / model / ann）。");
        }
        if (bool("turbofeed.feed.recall.vector.two-tower.enabled")) {
            String m = mode == null ? "hash" : mode;
            if (!Set.of("model", "ann").contains(m)) {
                fail("turbofeed.feed.recall.vector.two-tower.enabled=true 需 turbofeed.feed.recall.vector.mode=model 或 ann"
                        + "（当前=" + m + "），否则用户向量源不会被装配。");
            }
        }
    }

    /** 媒资存储：非法 storage 值会让三个客户端都不匹配，静默无存储实现。 */
    private void validateMediaStorage() {
        String storage = prop("turbofeed.media.storage");
        if (storage != null && !Set.of("minio", "local", "placeholder").contains(storage)) {
            fail("turbofeed.media.storage 取值非法：" + storage + "（允许 minio / local / placeholder）。");
        }
    }

    // ===== 工具 =====

    private String prop(String key) {
        String v = lookup.get(key);
        return v == null ? null : v.trim();
    }

    private boolean bool(String key) {
        String v = prop(key);
        return "true".equalsIgnoreCase(v);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    private static void fail(String msg) {
        throw new IllegalStateException("[FeatureFlagGuard] " + msg);
    }
}
