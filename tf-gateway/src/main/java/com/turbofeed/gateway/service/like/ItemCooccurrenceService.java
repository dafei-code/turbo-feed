package com.turbofeed.gateway.service.like;

import com.turbofeed.shared.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容-内容共现矩阵维护（记忆式 item-CF 的<b>写侧</b>，抖音式「收藏又收藏」补充召回通道的数据底座）。
 *
 * <p><b>存储</b>：每篇内容一个 Redis ZSET {@code tf:cf:item:{postId} => ZSET<neighborPostId, coocWeight>}
 * （共现权重 = 有多少用户「同时收藏了 postId 与 neighborPostId」的加权计数）。tf-feed-engine 的
 * {@code ItemCfRecallChannel} 只作为<b>读侧</b>消费它，本服务独占写。</p>
 *
 * <p><b>两种维护路径</b>：</p>
 * <ul>
 *   <li><b>增量（常态）</b>：{@link #onFavorite}/{@link #onUnfavorite} 在收藏 / 取消收藏时<b>对称</b>
 *   维护——用户收藏 A 时，把 A 与其「其余收藏」两两 {@code ZINCRBY}（双向写入，保证 {@code tf:cf:item:A}
 *   与 {@code tf:cf:item:other} 都含对方）；取消收藏时对称 {@code ZINCRBY(-w)}，权重归零即 {@code ZREM}。
 *   仅取用户<b>最近 {@code max-seeds} 条</b>收藏做关联，避免大 V 收藏量爆炸。</li>
 *   <li><b>批重建（默认关、冷启动 / 灾备用）</b>：{@link #rebuildAll} 读 {@code media_favorite} 全表，
 *   按用户分组两两累加后整体覆盖各帖共现 ZSET。会把全表对读入内存，<b>严禁高频调用</b>。</li>
 * </ul>
 *
 * <p><b>fail-open</b>：共现矩阵是推荐增强项，任一 Redis 操作异常只告警不抛，绝不阻断收藏主流程
 * （调用方 {@code MediaFavoriteService} 已在异步块里包了一层 try-catch）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ItemCooccurrenceService {

    private static final Logger log = LoggerFactory.getLogger(ItemCooccurrenceService.class);

    /** 共现 ZSET 整体 TTL：冷内容的共现信号自然消退，避免无限累积；热内容因持续被 ZINCRBY 而续期。 */
    private static final Duration CF_TTL = Duration.ofDays(14);

    private final StringRedisTemplate redisTemplate;
    private final MediaFavoriteJdbcRepository favoriteRepository;

    /** 单次收藏关联的其他收藏上限（控制大 V 收藏量下的写放大）。 */
    @Value("${turbofeed.feed.cf.max-seeds:50}")
    private int maxSeeds;
    /** 单帖共现邻居上限（ZSET 内存封顶，超则裁最低权重尾）。 */
    @Value("${turbofeed.feed.cf.max-neighbors:500}")
    private int maxNeighbors;
    /** 单条「同时收藏」边的权重（属强意图，与画像权重 1.5 对齐）。 */
    @Value("${turbofeed.feed.cf.favorite-weight:1.5}")
    private double favoriteWeight;

    /**
     * 用户收藏某内容时，维护它与该用户「其余收藏」的共现（双向）。
     *
     * @param postId 刚被收藏的内容（{@code MediaFavoriteService#addToUserFav} 已先行写入「我的收藏」ZSET）
     * @param userId 收藏者
     */
    public void onFavorite(String postId, long userId) {
        try {
            Set<String> others = recentFavorites(userId);
            if (others == null || others.isEmpty()) {
                return;
            }
            for (String other : others) {
                if (other.equals(postId)) {
                    continue; // 跳过刚加入的自己
                }
                bumpPair(postId, other);
            }
        } catch (Exception e) {
            log.warn("item-CF 增量维护失败（fail-open，不影响收藏主流程）: postId={}, userId={}, {}",
                    postId, userId, e.getMessage());
        }
    }

    /**
     * 用户取消收藏时，对称衰减它与「剩余收藏」的共现（双向）。
     *
     * @param postId 刚被取消收藏的内容（{@code MediaFavoriteService#removeFromUserFav} 已先行移除）
     * @param userId 收藏者
     */
    public void onUnfavorite(String postId, long userId) {
        try {
            Set<String> others = recentFavorites(userId);
            if (others == null || others.isEmpty()) {
                return;
            }
            for (String other : others) {
                decayPair(postId, other);
            }
        } catch (Exception e) {
            log.warn("item-CF 增量衰减失败（fail-open，不影响收藏主流程）: postId={}, userId={}, {}",
                    postId, userId, e.getMessage());
        }
    }

    /** 双向抬升一对内容的共现权重（A→B 与 B→A 同时 +w）。 */
    private void bumpPair(String a, String b) {
        redisTemplate.opsForZSet().incrementScore(RedisKeys.cfItem(a), b, favoriteWeight);
        redisTemplate.opsForZSet().incrementScore(RedisKeys.cfItem(b), a, favoriteWeight);
        trim(RedisKeys.cfItem(a));
        trim(RedisKeys.cfItem(b));
        touch(RedisKeys.cfItem(a));
        touch(RedisKeys.cfItem(b));
    }

    /** 双向衰减一对内容的共现权重（A→B 与 B→A 同时 -w）；权重归零即删除该邻居。 */
    private void decayPair(String a, String b) {
        Double wa = redisTemplate.opsForZSet().incrementScore(RedisKeys.cfItem(a), b, -favoriteWeight);
        Double wb = redisTemplate.opsForZSet().incrementScore(RedisKeys.cfItem(b), a, -favoriteWeight);
        if (wa != null && wa <= 0d) {
            redisTemplate.opsForZSet().remove(RedisKeys.cfItem(a), b);
        }
        if (wb != null && wb <= 0d) {
            redisTemplate.opsForZSet().remove(RedisKeys.cfItem(b), a);
        }
        touch(RedisKeys.cfItem(a));
        touch(RedisKeys.cfItem(b));
    }

    /** 共现 ZSET 续期（写路径统一兜底 TTL）。 */
    private void touch(String key) {
        try {
            redisTemplate.expire(key, CF_TTL);
        } catch (Exception ignore) {
            // 续期失败不影响权重
        }
    }

    /** 邻居数超过上限时，裁掉权重最低的尾（保留 Top-N）。 */
    private void trim(String key) {
        try {
            Long c = redisTemplate.opsForZSet().zCard(key);
            if (c != null && c > maxNeighbors) {
                redisTemplate.opsForZSet().removeRange(key, 0L, c - maxNeighbors - 1);
            }
        } catch (Exception e) {
            log.warn("item-CF 邻居裁剪失败（忽略）: key={}, {}", key, e.getMessage());
        }
    }

    /** 取用户最近 {@code max-seeds} 条收藏（含刚操作的那条，调用方自行跳过自身）。fail-open 空集。 */
    private Set<String> recentFavorites(long userId) {
        try {
            Set<String> s = redisTemplate.opsForZSet()
                    .reverseRange(RedisKeys.userFavorite(userId), 0, maxSeeds - 1);
            return s == null ? Set.of() : s;
        } catch (Exception e) {
            log.warn("item-CF 读取用户收藏失败（fail-open）: userId={}, {}", userId, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 批重建共现矩阵（冷启动 / Redis 拓扑重建后全量回灌；默认不调用，由运维手动或定时触发）。
     *
     * <p>会把 {@code media_favorite} 全表对读入内存，按用户分组两两累加后<b>整体覆盖</b>各帖共现 ZSET
     * （先 {@code DEL} 旧键再 {@code ZADD}，避免与既有增量数据重复计数）。权重用单位计数（1 次同收藏 = 1）。</p>
     */
    public void rebuildAll() {
        List<MediaFavoriteJdbcRepository.FavoritePair> pairs;
        try {
            pairs = favoriteRepository.listAllUserPostPairs();
        } catch (Exception e) {
            log.warn("item-CF 批重建读取全表失败（中断）: {}", e.getMessage());
            return;
        }
        if (pairs.isEmpty()) {
            log.info("item-CF 批重建：media_favorite 为空，无需重建");
            return;
        }
        // 按用户分组
        Map<Long, List<String>> byUser = new HashMap<>();
        for (MediaFavoriteJdbcRepository.FavoritePair p : pairs) {
            byUser.computeIfAbsent(p.userId(), k -> new ArrayList<>()).add(p.postId());
        }
        // 每用户两两累加共现（无向图：a-b 与 b-a 同一权）
        Map<String, Map<String, Double>> cooc = new HashMap<>();
        for (Map.Entry<Long, List<String>> e : byUser.entrySet()) {
            List<String> posts = e.getValue();
            for (int i = 0; i < posts.size(); i++) {
                for (int j = i + 1; j < posts.size(); j++) {
                    String a = posts.get(i);
                    String b = posts.get(j);
                    cooc.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1.0, Double::sum);
                    cooc.computeIfAbsent(b, k -> new HashMap<>()).merge(a, 1.0, Double::sum);
                }
            }
        }
        // 覆盖写入：先 DEL 旧键，再批量 ZADD 邻居（pipeline 写，按帖隔离，集群安全）
        for (Map.Entry<String, Map<String, Double>> e : cooc.entrySet()) {
            String key = RedisKeys.cfItem(e.getKey());
            try {
                redisTemplate.delete(key);
            } catch (Exception ignore) {
                // 删除失败不阻断后续写入
            }
            if (e.getValue().isEmpty()) {
                continue;
            }
            Map<String, Double> neighbors = e.getValue();
            try {
                redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                    for (Map.Entry<String, Double> n : neighbors.entrySet()) {
                        connection.zAdd(keyBytes, n.getValue(), n.getKey().getBytes(StandardCharsets.UTF_8));
                    }
                    connection.keyCommands().expire(keyBytes, CF_TTL.getSeconds());
                    return null;
                });
            } catch (Exception ex) {
                log.warn("item-CF 批重建写入失败（部分键可能缺失）: postId={}, {}", e.getKey(), ex.getMessage());
            }
        }
        log.info("item-CF 共现矩阵批重建完成: posts={}, rawPairs={}", cooc.size(), pairs.size());
    }
}
