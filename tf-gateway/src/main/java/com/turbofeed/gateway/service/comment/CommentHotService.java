package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论热头服务（写路径轻量 ZADD，读路径热头优先再桥接 DB keyset，支撑「快速增长的评论」）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>最新头 ZSET {@code tf:comment:recent:{mediaId}}</b>：score = {@code created_at} 毫秒戳，
 *       member = {@code commentId}。发布时 {@link #add(Comment)} 一次 {@code ZADD}（O(log N)）即完成
 *       索引写入，主链路不扫膨胀中的 DB 表。读路径「最新」首页直接 {@link #recentHead(String, int)}
 *       取最新 N 条 commentId（按 score 倒序 = 最新在前），再经 {@code WHERE media_id=? AND comment_id IN(...)}
 *       单分片取正文——首页永不扫大表。更深层由 {@link CommentJdbcRepository} 的 keyset 游标桥接。</li>
 *   <li><b>最热头 ZSET {@code tf:comment:hot:{mediaId}}</b>：score = {@code likeCount}（点赞实时加权）。
 *       点赞状态变化时 {@link #onLike(long, String, int)} 用 {@code ZINCRBY} 调整，读路径
 *       {@link #hotHead(String, int)} 取最热 N 条，对应抖音「最热评论」tab。</li>
 *   <li><b>最终一致取舍</b>：ZSET 带 TTL（8d），过期后最新首页回退 DB 取（{@link CommentJdbcRepository}
 *       有序索引仍高效）；极短窗口内新评论可能未及时进入 ZSET，属可接受的读最终一致。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentHotService {

    private static final Logger log = LoggerFactory.getLogger(CommentHotService.class);

    /** 最新头 ZSET：{@code tf:comment:recent:{mediaId}} score=createdAt(ms) member=commentId。 */
    private static final String RECENT_KEY_PREFIX = "tf:comment:recent:";
    /** 最热头 ZSET：{@code tf:comment:hot:{mediaId}} score=likeCount member=commentId。 */
    private static final String HOT_KEY_PREFIX = "tf:comment:hot:";
    /** ZSET TTL：略长于内容公域生命周期。 */
    private static final Duration TTL = Duration.ofDays(8);

    private final StringRedisTemplate redisTemplate;

    private static String recentKey(String mediaId) {
        return RECENT_KEY_PREFIX + mediaId;
    }

    private static String hotKey(String mediaId) {
        return HOT_KEY_PREFIX + mediaId;
    }

    /**
     * 发布一条评论时写入两个 ZSET（最新头 + 最热头初始 0）。fail-open：Redis 异常仅告警。
     *
     * <p><b>A3：4 次往返 → 1 次</b>。改造前是 {@code ZADD recent} → {@code ZADD hot} →
     * {@code EXPIRE recent} → {@code EXPIRE hot} 四次独立调用，大并发发布评论时往返开销 ×4，
     * 且中途失败会留下「进了 recent 没进 hot」或「无 TTL 的永久 Key」（后者是内存泄漏面）。
     * 现在四条命令在一次 pipeline 内发出：往返降为 1 次，失败窗口收窄到单次往返。</p>
     *
     * <p><b>为什么不用 Lua 脚本</b>：脚本要同时操作 recent 与 hot 两个 Key，而 Redis Cluster 下
     * 多 Key 脚本要求全部落在<b>同一槽</b>，否则报 {@code CROSSSLOT}（本项目已为此在
     * {@code RecommendedFeedService#invalidate} 放弃过批量 {@code DEL}）。要满足同槽就得给 Key 加
     * hashtag，而改 Key 名会让既有 ZSET 数据立即不可见——{@code hotHead} <b>没有 DB 回退</b>，
     * 最热 tab 会空窗整个 8d TTL。代价远高于收益，故选 pipeline：集群下多 Key 命令各自独立路由，
     * 无 CROSSSLOT 问题、零迁移。</p>
     *
     * <p><b>原子性非硬需求</b>：recent 写失败时读路径有 DB 回退
     * （{@code CommentService#listTopLevelFresh}）；hot 缺该 member 时，首次点赞的
     * {@code ZINCRBY} 会自动补建（初始分本就是 0，语义无损）。</p>
     */
    public void add(Comment c) {
        try {
            byte[] recentB = recentKey(c.mediaId()).getBytes(StandardCharsets.UTF_8);
            byte[] hotB = hotKey(c.mediaId()).getBytes(StandardCharsets.UTF_8);
            byte[] memberB = String.valueOf(c.commentId()).getBytes(StandardCharsets.UTF_8);
            double score = c.createdAt().toEpochMilli();
            long ttlSeconds = TTL.getSeconds();
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.zSetCommands().zAdd(recentB, score, memberB);
                connection.zSetCommands().zAdd(hotB, 0.0, memberB);
                connection.keyCommands().expire(recentB, ttlSeconds);
                connection.keyCommands().expire(hotB, ttlSeconds);
                return null;
            });
        } catch (Exception e) {
            log.warn("评论热头 ZADD 失败（fail-open，不影响发布）: mediaId={}, commentId={}, {}",
                    c.mediaId(), c.commentId(), e.getMessage());
        }
    }

    /** 点赞状态变化时调整最热头分数（delta=+1 新增赞 / -1 取消赞）。fail-open。 */
    public void onLike(long commentId, String mediaId, int delta) {
        try {
            redisTemplate.opsForZSet().incrementScore(hotKey(mediaId), String.valueOf(commentId), delta);
        } catch (Exception e) {
            log.warn("评论最热头 ZINCRBY 失败（fail-open）: mediaId={}, commentId={}, delta={}, {}",
                    mediaId, commentId, delta, e.getMessage());
        }
    }

    /** 最新头：取最新 N 条 commentId（score 倒序）。空结果表示 ZSET 冷/空，调用方回退 DB。 */
    public List<Long> recentHead(String mediaId, int size) {
        try {
            Set<String> ids = redisTemplate.opsForZSet().reverseRange(recentKey(mediaId), 0, size - 1);
            return toLongs(ids);
        } catch (Exception e) {
            log.warn("评论最新头读取失败（回退 DB）: mediaId={}, {}", mediaId, e.getMessage());
            return List.of();
        }
    }

    /** 最热头：取最热 N 条 commentId（score 倒序）。 */
    public List<Long> hotHead(String mediaId, int size) {
        try {
            Set<String> ids = redisTemplate.opsForZSet().reverseRange(hotKey(mediaId), 0, size - 1);
            return toLongs(ids);
        } catch (Exception e) {
            log.warn("评论最热头读取失败（回退空）: mediaId={}, {}", mediaId, e.getMessage());
            return List.of();
        }
    }

    private static List<Long> toLongs(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(Long::parseLong).collect(Collectors.toList());
    }
}
