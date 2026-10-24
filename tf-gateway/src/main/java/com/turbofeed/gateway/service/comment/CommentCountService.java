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

    /**
     * 发布一条评论时 +1（顶层 / 回复均计入内容总评论数）。fail-open：Redis 异常仅告警。
     *
     * <p><b>A8 冷启动校正（修真实缺陷）</b>：{@code INCR} 对<b>不存在</b>的 Key 会创建为 0 再 +1。
     * 因此 Redis 重启 / Key 过期后的第一条评论会把总数变成 1，而<b>不是</b>「历史总数 + 1」；
     * 更糟的是此后 Key 已存在，{@link #getCount(String)} 的 DB 回退<b>永不触发</b>，
     * 于是一个有上千条评论的内容会长期显示个位数。判定依据：{@code INCR} 返回 1 即说明 Key 是本次新建的。
     * 此时用 {@code COUNT(*)} 取 DB 真实值，再以 {@code INCRBY} 原子补上差额。</p>
     *
     * <p><b>为什么用 INCRBY 而不是 SET</b>：评论落库是异步的（见 {@code CommentService}），
     * 校正窗口内可能有并发评论也在 {@code INCR}；无条件 {@code SET} 会覆盖掉这些增量，
     * {@code INCRBY} 则只补差额、不丢并发写入。补完后 Key = {@code dbCount + 1}
     * （当前这条尚未落库，故 {@code dbCount} 不含它），正是正确总数。</p>
     */
    public void incr(String mediaId) {
        try {
            String key = COUNT_KEY + mediaId;
            Long v = redisTemplate.opsForValue().increment(key);
            if (v != null && v == 1L) {
                // Key 为本次新建 → 冷启动，需以 DB 真实值校正，否则总数从 1 重新累加
                long db = commentRepository.countAll(mediaId);
                if (db > 0) {
                    redisTemplate.opsForValue().increment(key, db);
                    log.info("评论总数冷启动校正（Redis Key 缺失，按 DB 补齐差额）: mediaId={}, dbCount={}", mediaId, db);
                }
            }
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("评论总数 INCR 失败（fail-open，不影响发布）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }

    /**
     * 读某内容总评论数（Redis 优先，冷启动回退 DB 并预热）。
     *
     * <p><b>A4 滑动续期</b>：{@code GET} 与 {@code EXPIRE} 合并进一次 pipeline（往返次数不变），
     * 使「仍在被浏览的内容」计数 Key 不会因固定 8d TTL 到期而回到冷启动路径。</p>
     */
    public long getCount(String mediaId) {
        String key = COUNT_KEY + mediaId;
        try {
            List<Object> pipelined = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] kb = key.getBytes(StandardCharsets.UTF_8);
                connection.stringCommands().get(kb);
                // EXPIRE 对不存在的 Key 是 no-op，不会误建空 Key
                connection.keyCommands().expire(kb, TTL.getSeconds());
                return null;
            });
            Object v = pipelined.isEmpty() ? null : pipelined.get(0);
            if (v != null) {
                return Long.parseLong(v.toString());
            }
            long db = commentRepository.countAll(mediaId);
            try {
                redisTemplate.opsForValue().set(key, String.valueOf(db), TTL);
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
