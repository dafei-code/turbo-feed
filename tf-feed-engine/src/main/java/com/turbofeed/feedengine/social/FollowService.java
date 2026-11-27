package com.turbofeed.feedengine.social;

import com.turbofeed.shared.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关注关系读取（抖音式「关注流 / 社交分发」G6，推荐消费侧）。
 *
 * <p><b>读模型</b>：关注关系由网关写入（同 Redis keyspace），约定
 * {@code tf:follow:{userId}} = ZSET(followedAuthorId → 关注时刻毫秒)。本服务只负责在推荐读路径
 * 读取该社交图，把「关注作者」的近期已审内容混入发现流——<b>不做任何写入</b>，写入留网关。</p>
 *
 * <p><b>fail-open</b>：关系缺失 / Redis 不可达 / 解析异常 → 返回空集，发现流退化为纯公域
 * （热点 + 兴趣 + 向量 + 流量池），绝不影响浏览主流程。</p>
 */
@Component
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);
    /** 关注关系键前缀（网关写入，本服务只读）：tf:follow:{userId} = ZSET(authorId → 关注时刻)。 */
    private static final String FOLLOW_PREFIX = RedisKeys.FOLLOW;
    /** 社交计数键前缀（网关写入）：tf:user:stat:{authorId} = HASH(following / followers)。 */
    private static final String USER_STAT_PREFIX = "tf:user:stat:";
    private static final String FOLLOWERS_FIELD = "followers";

    private final StringRedisTemplate redisTemplate;

    /** 大 V 优先扫描开关：默认 false，与改造前顺序完全一致（灰度用）。 */
    @Value("${turbofeed.feed.follow.bigv-first-enabled:false}")
    private boolean bigvFirstEnabled;
    /** 大 V 粉丝数阈值： followers >= 该值视为大 V。 */
    @Value("${turbofeed.feed.follow.bigv-follower-threshold:100000}")
    private long bigvFollowerThreshold;
    /** 大 V 扫描窗口：最多读多少个关注作者的粉丝数（限制 Redis pipeline 规模）。 */
    @Value("${turbofeed.feed.follow.bigv-scan-window:200}")
    private int bigvScanWindow;

    public FollowService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 该用户是否关注了任何人（快速判空，避免无关注时走召回）。fail-open：异常→false。 */
    public boolean hasFollows(String userId) {
        if (userId == null || userId.isEmpty()) {
            return false;
        }
        try {
            Long c = redisTemplate.opsForZSet().zCard(FOLLOW_PREFIX + userId);
            return c != null && c > 0;
        } catch (Exception e) {
            log.warn("关注关系读取失败（fail-open 视为无关注）: userId={}, {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 取该用户关注的用户（按关注时刻倒序，最新关注的在前；限制扫描上限，避免大 V 关注数爆炸）。
     *
     * @param userId 用户
     * @param limit  最多返回的关注数（≤0 → 空集）
     * @return 关注作者 id 列表（顺序 = 关注时刻倒序）；缺失 / 异常 → 空集
     */
    public List<String> followedAuthors(String userId, int limit) {
        if (userId == null || userId.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            Set<String> s = redisTemplate.opsForZSet().reverseRange(FOLLOW_PREFIX + userId, 0, limit - 1);
            return s == null ? List.of() : new ArrayList<>(s);
        } catch (Exception e) {
            log.warn("关注列表读取失败（fail-open 退化为纯公域）: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 大 V 优先的关注作者列表（P0：解决「大 V 排在关注列表 50 名之外被截断」）。
     *
     * <p><b>做法</b>：<ol>
     *   <li>按关注时刻倒序扫一个更大的窗口（{@link #bigvScanWindow}，默认 200）；</li>
     *   <li>一次 pipeline 批量 HGET 各作者的 {@code tf:user:stat:{authorId}.followers}，避免 N 次往返；</li>
     *   <li>大 V（followers ≥ 阈值）排到前面、组内仍按关注时刻倒序；普通作者依次跟随；</li>
     *   <li>截取 {@code limit} 条，同时返回被选中的大 V 集合供召回侧做动态配额。</li>
     * </ol></p>
     *
     * <p><b>开关关闭 / 读失败</b>：完全退化为 {@link #followedAuthors} 的原顺序（关注时刻倒序），
     * 大 V 集合为空——零回归，符合灰度要求。</p>
     *
     * @return 排序后的作者列表 + 其中的大 V 集合（均 fail-open）
     */
    public RankedFollowees rankedFollowees(String userId, int limit) {
        if (!bigvFirstEnabled || userId == null || userId.isEmpty() || limit <= 0) {
            return new RankedFollowees(followedAuthors(userId, limit), Set.of());
        }
        int window = Math.max(limit, Math.min(bigvScanWindow, 1000));
        List<String> candidates = followedAuthors(userId, window);
        if (candidates.isEmpty()) {
            return new RankedFollowees(List.of(), Set.of());
        }
        try {
            Map<String, Long> counts = followerCountsOf(candidates);
            List<String> big = new ArrayList<>();
            List<String> normal = new ArrayList<>();
            for (String aid : candidates) {
                long followers = counts.getOrDefault(aid, 0L);
                if (followers >= bigvFollowerThreshold) {
                    big.add(aid);
                } else {
                    normal.add(aid);
                }
            }
            big.addAll(normal);
            List<String> ordered = big.size() <= limit ? big : new ArrayList<>(big.subList(0, limit));
            Set<String> bigVSet = new HashSet<>();
            for (String aid : ordered) {
                if (counts.getOrDefault(aid, 0L) >= bigvFollowerThreshold) {
                    bigVSet.add(aid);
                }
            }
            if (!bigVSet.isEmpty()) {
                log.info("关注流大 V 优先已生效: userId={}, 候选={}, 入选大 V={}, 阈值={}",
                        userId, candidates.size(), bigVSet.size(), bigvFollowerThreshold);
            }
            return new RankedFollowees(ordered, bigVSet);
        } catch (Exception e) {
            log.warn("大 V 优先排序失败（fail-open 退回关注时刻顺序）: userId={}, {}", userId, e.getMessage());
            return new RankedFollowees(followedAuthors(userId, limit), Set.of());
        }
    }

    /** 一次 pipeline 批量 HGET 作者粉丝数；读不到的作者按 0 处理（fail-open）。 */
    private Map<String, Long> followerCountsOf(List<String> authorIds) {
        Map<String, Long> result = new HashMap<>();
        if (authorIds.isEmpty()) {
            return result;
        }
        List<Object> vals = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String aid : authorIds) {
                connection.hashCommands().hGet(
                        (USER_STAT_PREFIX + aid).getBytes(StandardCharsets.UTF_8),
                        FOLLOWERS_FIELD.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        for (int i = 0; i < authorIds.size(); i++) {
            Object v = vals == null || i >= vals.size() ? null : vals.get(i);
            result.put(authorIds.get(i), parseLong(v));
        }
        return result;
    }

    private static long parseLong(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(new String((byte[]) v, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 大 V 优先排序结果：有序作者列表 + 其中的大 V 集合。 */
    public record RankedFollowees(List<String> authors, Set<String> bigVAuthors) {
    }
}
