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
    /** 完播率阈值：观看进度达到该比例才记一次"完播"（计入 playCompletes）。 */
    private static final double PLAY_COMPLETE_RATIO = 0.7;

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

    /**
     * 完播上报（携带观看时长）：曝光 +1 恒定（抖音式"划到即曝光"）；
     * 观看比例 {@code watchDuration / mediaDuration} 达到 {@link #PLAY_COMPLETE_RATIO} 再记一次完播。
     *
     * <p><b>为什么是比例而非二进制</b>：旧端只发 {@code PLAY_COMPLETE}（看完即 +1），新端发
     * {@code WATCH} 携带精确进度——比例更贴近抖音"完播率"语义，且能区分"看了 30%"与"看了 99%"。
     * 二者都写入同一个 {@code playCompletes} 字段，晋级器消费口径不变。</p>
     */
    public void recordWatch(String timelineKey, Integer watchDuration, Integer mediaDuration) {
        if (timelineKey == null) {
            return;
        }
        int md = mediaDuration != null ? mediaDuration : 0;
        int wd = watchDuration != null ? watchDuration : 0;
        try {
            redisTemplate.opsForHash().increment(STAT_PREFIX + timelineKey, "impressions", 1);
            redisTemplate.expire(STAT_PREFIX + timelineKey, STAT_TTL);
            redisTemplate.opsForSet().add(TRACKED_KEY, timelineKey);
            if (md > 0 && (double) wd / md >= PLAY_COMPLETE_RATIO) {
                redisTemplate.opsForHash().increment(STAT_PREFIX + timelineKey, "playCompletes", 1);
            }
        } catch (Exception e) {
            log.warn("完播埋点写入失败（不影响主流程）: timelineKey={}, watch={}, media={}, {}",
                    timelineKey, wd, md, e.getMessage());
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
