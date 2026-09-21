package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 内容实时分（抖音式赛马的数据底座）。
 *
 * <p>每条内容维护一个 Redis Hash {@code tf:post:stat:{timelineKey}}，字段为
 * {@code impressions / playCompletes / likes / comments / shares / dislikes}——
 * 行为事件（曝光/完播/点赞/评论/分享/不感兴趣）实时累加，即"内容实时分"。</p>
 *
 * <p><b>为什么用 Hash 而不是新存储</b>：用户明确要求"复用现有 Redis，不引新存储"。
 * 统计本就是高频小写入，Hash 的 {@code HINCRBY} 原子自增最契合；与公域时间线共用同一 Redis，
 * 不引入任何新中间件。</p>
 *
 * <p><b>待评估集合 {@code tf:feed:stat:tracked}</b>：行为事件触发时把 {@code timelineKey} 加入。
 * 晋级器只扫这个有界集合，<b>不</b>对 {@code tf:post:stat:*} 做 SCAN（避免大 keyspace 扫描抖动）。
 * 帖子晋级到顶池或离开公域（下架/过期）时从集合移除。</p>
 *
 * <p>fail-open：统计丢失只告警，绝不影响审核/发布主流程——埋点是优化项，不是正确性依赖。</p>
 */
@Service
public class PostStatService {

    private static final Logger log = LoggerFactory.getLogger(PostStatService.class);

    private final StringRedisTemplate redisTemplate;

    /** 每帖实时分：{@code tf:post:stat:{timelineKey}} => Hash(各行为计数)。 */
    static final String STAT_PREFIX = "tf:post:stat:";
    /** 待晋级评估的帖集合（有界，避免 SCAN）。 */
    static final String TRACKED_KEY = "tf:feed:stat:tracked";
    /** 统计存活时长：略长于时间线桶 TTL，内容离开公域后统计自然过期。 */
    private static final Duration STAT_TTL = Duration.ofDays(8);

    public PostStatService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 单条行为事件：写入对应计数 + 加入待评估集合。未知类型静默忽略。 */
    public void record(String timelineKey, String type) {
        if (timelineKey == null || type == null) {
            return;
        }
        String field = toField(type);
        if (field == null) {
            return;
        }
        try {
            redisTemplate.opsForHash().increment(STAT_PREFIX + timelineKey, field, 1);
            redisTemplate.expire(STAT_PREFIX + timelineKey, STAT_TTL);
            redisTemplate.opsForSet().add(TRACKED_KEY, timelineKey);
        } catch (Exception e) {
            log.warn("行为埋点写入失败（不影响主流程）: timelineKey={}, type={}, {}", timelineKey, type, e.getMessage());
        }
    }

    /** 批量记录（一次请求多个事件；网关 HTTP 路径整批投递）。 */
    public void recordAll(List<FeedBehaviorEvent> events) {
        if (events == null) {
            return;
        }
        for (FeedBehaviorEvent e : events) {
            record(e.timelineKey(), e.type());
        }
    }

    /** 读取某帖的实时分快照（晋级器用）。 */
    public PostStat snapshot(String timelineKey) {
        try {
            Map<Object, Object> m = redisTemplate.opsForHash().entries(STAT_PREFIX + timelineKey);
            return new PostStat(
                    asLong(m.get("impressions")),
                    asLong(m.get("playCompletes")),
                    asLong(m.get("likes")),
                    asLong(m.get("comments")),
                    asLong(m.get("shares")),
                    asLong(m.get("dislikes")));
        } catch (Exception e) {
            log.warn("行为分读取失败（按零分处理）: timelineKey={}, {}", timelineKey, e.getMessage());
            return new PostStat(0, 0, 0, 0, 0, 0);
        }
    }

    private static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ignore) {
            return 0L;
        }
    }

    /** 行为类型 → Hash 字段名；未知返回 null。大小写不敏感。 */
    private static String toField(String type) {
        return switch (type) {
            case "IMPRESSION", "impression" -> "impressions";
            case "PLAY_COMPLETE", "play_complete" -> "playCompletes";
            case "LIKE", "like" -> "likes";
            case "COMMENT", "comment" -> "comments";
            case "SHARE", "share" -> "shares";
            case "DISLIKE", "dislike", "NOT_INTERESTED", "not_interested" -> "dislikes";
            default -> null;
        };
    }

    /**
     * 某帖的实时分快照。
     *
     * @param impressions  曝光
     * @param playCompletes 完播
     * @param likes        点赞
     * @param comments     评论
     * @param shares       分享
     * @param dislikes     不感兴趣/负向
     */
    public record PostStat(long impressions, long playCompletes, long likes,
                           long comments, long shares, long dislikes) {
    }
}
