package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 评论总数计数服务（支撑「快速增长的评论」实时计数，避免 {@code SELECT COUNT(*)} 扫分片）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>权威计数 = Redis {@code INCR}</b>：每条评论发布时 {@link #incr(String)} 原子 +1（顶层与回复
 *       都计入内容总评论数），读路径 {@link #getCount(String)} 走 {@code GET}（O(1)），彻底规避
 *       {@code COUNT(*)} 对持续膨胀的 {@code comment} 表做全分片扫描。</li>
 *   <li><b>冷启动兜底</b>：Redis Key 缺失（新内容 / Key 过期）时回退 {@code COUNT(*)} 并写回 Redis 预热，
 *       保证计数最终一致；任何 Redis 异常也都回退 DB，绝不把计数失败抛给用户。</li>
 *   <li><b>TTL</b>：与内容公域生命周期对齐（8d），过期后由下一次读路径冷启动重建。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentCountService {

    private static final Logger log = LoggerFactory.getLogger(CommentCountService.class);

    /** 评论总数 Key：{@code tf:comment:count:{mediaId} => 总评论数（含回复）}。 */
    private static final String COUNT_KEY = "tf:comment:count:";
    /** 计数 Key TTL：略长于内容公域生命周期。 */
    private static final Duration TTL = Duration.ofDays(8);

    private final StringRedisTemplate redisTemplate;
    private final CommentJdbcRepository commentRepository;

    /** 发布一条评论时 +1（顶层 / 回复均计入内容总评论数）。fail-open：Redis 异常仅告警。 */
    public void incr(String mediaId) {
        try {
            String key = COUNT_KEY + mediaId;
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("评论总数 INCR 失败（fail-open，不影响发布）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }

    /** 读某内容总评论数（Redis 优先，冷启动回退 DB 并预热）。 */
    public long getCount(String mediaId) {
        try {
            String v = redisTemplate.opsForValue().get(COUNT_KEY + mediaId);
            if (v != null) {
                return Long.parseLong(v);
            }
            long db = commentRepository.countAll(mediaId);
            try {
                redisTemplate.opsForValue().set(COUNT_KEY + mediaId, String.valueOf(db), TTL);
            } catch (Exception ignore) {
                // 预热失败不影响本次返回
            }
            return db;
        } catch (Exception e) {
            log.warn("评论总数读取失败（回退 DB）: mediaId={}, {}", mediaId, e.getMessage());
            return commentRepository.countAll(mediaId);
        }
    }
}
