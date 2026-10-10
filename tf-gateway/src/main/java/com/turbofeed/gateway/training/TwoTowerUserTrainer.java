package com.turbofeed.gateway.training;

import com.turbofeed.gateway.repository.InteractionEventRepository;
import com.turbofeed.gateway.repository.InteractionEventRow;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A1 离线双塔用户向量训练作业（抖音式「用户向量」信号⑤的真正来源）。
 *
 * <p><b>为什么在网关</b>：交互样本 {@code interaction_event} 由网关写库（与 {@code behavior_log} 同套路），
 * 而 feed-engine 是纯 Redis + MQ 引擎、无 DataSource。训练作业需要读样本，故落在网关；
 * 产出的用户向量写 Redis KV {@code tf:emb:user:{userId}}，引擎侧 {@code RedisUserEmbeddingSource}
 * 直接读这份 KV（与 A0「向量经 Redis KV 跨模块解耦」完全一致），零跨模块代码耦合。</p>
 *
 * <p><b>算法（轻量双塔）</b>：
 * <ul>
 *   <li>User 塔：取用户近 N 天有序交互序列（来自 {@code interaction_event}），
 *       将每条交互的 item 向量（{@code tf:emb:item:}，由 A0 的 {@code TagCooccurrenceTrainer} 写入）
 *       按「行为权重 × recency 衰减」加权均值池化，L2 归一 → 用户向量。</li>
 *   <li>Item 塔：复用 A0 已训好的 {@code tf:emb:item:}（标签向量均值），本作业不重训 item 塔。</li>
 * </ul>
 * 行为权重对齐 {@code InterestService} 约定（SHARE/COMMENT=1.5、LIKE=1.0、CLICK=0.6、WATCH=0.4），
 * 曝光/负反馈不进正向量。recency 按配置半衰期（默认 7 天）指数衰减，越久的交互贡献越小。</p>
 *
 * <p><b>触发与默认</b>：默认关闭（{@code two-tower.enabled=false}），样本沉淀足够后再开；
 * 定时 {@code cron}（默认 03:30）跑，亦可手动 {@link #train()} 触发（e2e 探针用）。
 * 任何异常 fail-open：整体失败只告警，单用户/KV 写失败单条跳过，绝不阻断网关启动或浏览。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class TwoTowerUserTrainer {

    private static final Logger log = LoggerFactory.getLogger(TwoTowerUserTrainer.class);

    private final InteractionEventRepository repository;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String itemPrefix;
    private final String userPrefix;
    private final int dim;
    private final int sinceDays;
    private final int sampleLimit;
    private final double recencyHalfLifeDays;
    private final int userCap;

    public TwoTowerUserTrainer(InteractionEventRepository repository,
                               StringRedisTemplate redis,
                               @Value("${turbofeed.feed.recall.vector.two-tower.enabled:false}") boolean enabled,
                               @Value("${turbofeed.feed.recall.vector.item-prefix:tf:emb:item:}") String itemPrefix,
                               @Value("${turbofeed.feed.recall.vector.user-prefix:tf:emb:user:}") String userPrefix,
                               @Value("${turbofeed.feed.recall.vector.dim:64}") int dim,
                               @Value("${turbofeed.feed.recall.vector.two-tower.since-days:7}") int sinceDays,
                               @Value("${turbofeed.feed.recall.vector.two-tower.sample-limit:200000}") int sampleLimit,
                               @Value("${turbofeed.feed.recall.vector.two-tower.recency-half-life-days:7}") double recencyHalfLifeDays,
                               @Value("${turbofeed.feed.recall.vector.two-tower.user-cap:100000}") int userCap) {
        this.repository = repository;
        this.redis = redis;
        this.enabled = enabled;
        this.itemPrefix = itemPrefix;
        this.userPrefix = userPrefix;
        this.dim = dim;
        this.sinceDays = sinceDays;
        this.sampleLimit = sampleLimit;
        this.recencyHalfLifeDays = recencyHalfLifeDays;
        this.userCap = userCap;
    }

    /** 夜间定时训练（默认关）。fail-open：整体异常只告警。 */
    @Scheduled(cron = "${turbofeed.feed.recall.vector.two-tower.cron:0 30 3 * * ?}")
    public void scheduledTrain() {
        if (!enabled) {
            return;
        }
        try {
            int wrote = train();
            if (wrote == 0) {
                log.info("双塔用户向量训练：未写入（可能 item 向量尚未生成，需 recall.vector.mode=model 且训练器已跑）");
            }
        } catch (Exception e) {
            log.warn("双塔用户向量训练（定时）失败（fail-open）: {}", e.getMessage());
        }
    }

    /** 手动触发训练（运维/探针）。返回写入的用户向量条数。 */
    public int train() {
        if (!enabled) {
            log.info("双塔用户向量训练：开关关闭，跳过");
            return 0;
        }
        Instant from = Instant.now().minus(Duration.ofDays(sinceDays));
        List<InteractionEventRow> rows;
        try {
            rows = repository.listSince(from, sampleLimit);
        } catch (Exception e) {
            log.warn("双塔用户向量训练：样本拉取失败（fail-open）: {}", e.getMessage());
            return 0;
        }
        if (rows.isEmpty()) {
            log.info("双塔用户向量训练：近 {} 天无交互样本，跳过", sinceDays);
            return 0;
        }
        // 按 user 分组（保留时间序，供 recency 衰减）
        Map<Long, List<InteractionEventRow>> byUser = new LinkedHashMap<>();
        for (InteractionEventRow r : rows) {
            byUser.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        int wrote = 0;
        int skipped = 0;
        Instant now = Instant.now();
        for (Map.Entry<Long, List<InteractionEventRow>> e : byUser.entrySet()) {
            if (wrote >= userCap) {
                break;
            }
            long userId = e.getKey();
            double[] vec = buildUserVector(e.getValue(), now);
            if (vec == null) {
                skipped++;
                continue;
            }
            try {
                redis.opsForValue().set(userPrefix + userId, EmbeddingCodec.encode(vec));
                wrote++;
            } catch (Exception ex) {
                log.warn("双塔用户向量训练：写 KV 失败 userId={}, {}", userId, ex.getMessage());
            }
        }
        log.info("双塔用户向量训练完成：样本={}, 用户={}, 写入={}, 跳过={}", rows.size(), byUser.size(), wrote, skipped);
        return wrote;
    }

    /** 用户向量 = 其交互 item 向量（tf:emb:item:）按「行为权重 × recency 衰减」加权均值后 L2 归一。 */
    private double[] buildUserVector(List<InteractionEventRow> events, Instant now) {
        double[] acc = new double[dim];
        double norm = 0.0;
        for (InteractionEventRow r : events) {
            double w = eventWeight(r.eventType());
            if (w <= 0.0) {
                continue; // 曝光/负反馈不进正向量
            }
            double[] itemVec = decodeItem(r.itemId());
            if (itemVec == null || itemVec.length != dim) {
                continue;
            }
            double weight = w * recencyWeight(r.createdAt(), now);
            for (int i = 0; i < dim; i++) {
                acc[i] += weight * itemVec[i];
            }
            norm += Math.abs(weight);
        }
        if (norm <= 0.0) {
            return null;
        }
        double sum = 0.0;
        for (double x : acc) {
            sum += x * x;
        }
        double n = Math.sqrt(sum);
        if (n == 0.0) {
            return null;
        }
        for (int i = 0; i < dim; i++) {
            acc[i] /= n;
        }
        return acc;
    }

    private double[] decodeItem(String itemId) {
        if (itemId == null) {
            return null;
        }
        try {
            return EmbeddingCodec.decode(redis.opsForValue().get(itemPrefix + itemId));
        } catch (Exception e) {
            return null;
        }
    }

    private double recencyWeight(Instant ts, Instant now) {
        if (recencyHalfLifeDays <= 0) {
            return 1.0;
        }
        long ageDays = Duration.between(ts, now).toDays();
        if (ageDays < 0) {
            ageDays = 0;
        }
        return Math.pow(0.5, (double) ageDays / recencyHalfLifeDays);
    }

    /** 行为类型 → 训练权重（与 InterestService 约定对齐：强意图更高）。 */
    private static double eventWeight(String type) {
        if (type == null) {
            return 0.0;
        }
        return switch (type) {
            case "SHARE", "COMMENT" -> 1.5;
            case "LIKE" -> 1.0;
            case "CLICK" -> 0.6;
            case "WATCH", "PLAY_COMPLETE" -> 0.4;
            default -> 0.0; // IMPRESSION / DISLIKE / NOT_INTERESTED 不进正向量
        };
    }
}
