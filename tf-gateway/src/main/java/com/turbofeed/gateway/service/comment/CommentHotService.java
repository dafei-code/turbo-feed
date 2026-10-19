package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    /** 发布一条评论时写入两个 ZSET（最新头 + 最热头初始 0）。fail-open：Redis 异常仅告警。 */
    public void add(Comment c) {
        try {
            String recent = recentKey(c.mediaId());
            String hot = hotKey(c.mediaId());
            redisTemplate.opsForZSet().add(recent, String.valueOf(c.commentId()), c.createdAt().toEpochMilli());
            redisTemplate.opsForZSet().add(hot, String.valueOf(c.commentId()), 0.0);
            redisTemplate.expire(recent, TTL);
            redisTemplate.expire(hot, TTL);
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
