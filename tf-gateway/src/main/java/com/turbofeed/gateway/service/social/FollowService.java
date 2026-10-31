package com.turbofeed.gateway.service.social;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.notify.NotificationEvent;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.notify.NotificationType;
import com.turbofeed.shared.redis.RedisKeys;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 关注关系服务（抖音式社交图：拉模式，零 fan-out，Redis 读写权威）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>Redis 为权威读源</b>：{@code tf:follow:{userId}} = ZSET(authorId→关注时刻ms) 供 G6 关注流
 *       （引擎 FollowService 只读此键）；{@code tf:follower:{userId}} = ZSET(followerId→时刻) 供粉丝列表；
 *       {@code tf:user:stat:{userId}} HASH 存 following/followers 计数。关注/取关的<b>热路径只写 Redis</b>，
 *       主流程零 DB 写，天然支撑高并发。</li>
 *   <li><b>DB 为持久化兜底</b>：关系变更后经 {@code interactionExecutor} 异步双写 user_follow / user_fans
 *       （fail-open，落库失败只告警，不影响主流程）。重启后由管理端点从 DB 重建 Redis。</li>
 *   <li><b>互关判定</b>：以 Redis 为准——{@code tf:follow:{authorId}} 是否含 userId。无需查 DB，
 *       反向关系实时可得（抖音式「互相关注」即时显示）。</li>
 *   <li><b>零 fan-out</b>：大 V 被关注只写 2 行 DB + 2 次 ZADD，<b>不</b>向粉丝逐条推送，
 *       粉丝读关注流时拉取（pull），避免大 V 扇出风暴。</li>
 *   <li><b>幂等</b>：ZADD/ZREM 返回 1 才视为状态变化，重复关注/取关返回幂等结果；计数用 HINCRBY
 *       只在状态变化时增减，不漂移。</li>
 *   <li><b>fail-open</b>：Redis/DB/Executor 任一异常按既定策略兜底，绝不影响浏览主流程。</li>
 * </ul>
 */
@Service
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    /**
     * 我关注的作者：tf:follow:{userId} = ZSET(authorId → 关注时刻ms)。引擎 G6 只读此键。
     *
     * <p><b>跨服务契约</b>：字面量统一来自 {@link RedisKeys#FOLLOW}，避免两侧各写一份漂移。</p>
     */
    static final String FOLLOW_PREFIX = RedisKeys.FOLLOW;
    /**
     * 我的粉丝：tf:follower:{userId} = ZSET(followerId → 关注时刻ms)。
     *
     * <p><b>跨服务契约</b>：字面量统一来自 {@link RedisKeys#FOLLOWER}。</p>
     */
    static final String FOLLOWER_PREFIX = RedisKeys.FOLLOWER;
    /** 用户社交计数：tf:user:stat:{userId} HASH(following / followers)。 */
    static final String STAT_PREFIX = "tf:user:stat:";
    static final String FOLLOWING_FIELD = "following";
    static final String FOLLOWERS_FIELD = "followers";

    private final StringRedisTemplate redisTemplate;
    private final FollowRepository followRepository;
    private final Executor interactionExecutor;
    /** 通知生成器（ADR-0009，fail-open 单点生成）：关注通知被关注者。 */
    private final NotificationGenerator notificationGenerator;

    public FollowService(StringRedisTemplate redisTemplate, FollowRepository followRepository,
                         @Qualifier("interactionExecutor") Executor interactionExecutor,
                         NotificationGenerator notificationGenerator) {
        this.redisTemplate = redisTemplate;
        this.followRepository = followRepository;
        this.interactionExecutor = interactionExecutor;
        this.notificationGenerator = notificationGenerator;
    }

    /**
     * 关注某作者（抖音式）。
     *
     * @return {@code true}=新关注（状态变化）；{@code false}=原本已关注（幂等）
     * @throws BizException 关注自己时抛 {@code PARAM_ERROR}
     */
    public boolean follow(long userId, long authorId) {
        if (userId == authorId) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能关注自己");
        }
        String fuKey = FOLLOW_PREFIX + userId;
        String frKey = FOLLOWER_PREFIX + authorId;
        long now = System.currentTimeMillis();
        try {
            Boolean added = redisTemplate.opsForZSet().add(fuKey, String.valueOf(authorId), now);
            boolean fresh = Boolean.TRUE.equals(added);
            if (fresh) {
                redisTemplate.opsForZSet().add(frKey, String.valueOf(userId), now);
                redisTemplate.opsForHash().increment(STAT_PREFIX + userId, FOLLOWING_FIELD, 1);
                redisTemplate.opsForHash().increment(STAT_PREFIX + authorId, FOLLOWERS_FIELD, 1);
                // 通知被关注者（ADR-0009，fail-open 单点生成，绝不阻塞关注主流程）
                notifyFollow(userId, authorId);
            }
            // 互关判定（Redis 为准）：对方是否也关注了我
            boolean mutual = redisTemplate.opsForZSet().score(FOLLOW_PREFIX + authorId,
                    String.valueOf(userId)) != null;
            long finalUserId = userId;
            long finalAuthorId = authorId;
            boolean finalMutual = mutual;
            interactionExecutor.execute(() -> {
                try {
                    followRepository.upsertFollow(finalUserId, finalAuthorId, finalMutual);
                    followRepository.upsertFan(finalAuthorId, finalUserId, finalMutual);
                } catch (Exception ex) {
                    log.warn("关注关系异步落库失败（fail-open）: userId={}, authorId={}, {}",
                            finalUserId, finalAuthorId, ex.getMessage());
                }
            });
            return fresh;
        } catch (Exception e) {
            log.warn("关注失败（fail-open，按未关注兜底）: userId={}, authorId={}, {}",
                    userId, authorId, e.getMessage());
            return false;
        }
    }

    /**
     * 取消关注某作者。
     *
     * @return {@code true}=确有取关（状态变化）；{@code false}=原本未关注（幂等）
     */
    public boolean unfollow(long userId, long authorId) {
        if (userId == authorId) {
            return false;
        }
        String fuKey = FOLLOW_PREFIX + userId;
        String frKey = FOLLOWER_PREFIX + authorId;
        try {
            Long removed = redisTemplate.opsForZSet().remove(fuKey, String.valueOf(authorId));
            boolean changed = removed != null && removed == 1L;
            if (changed) {
                redisTemplate.opsForZSet().remove(frKey, String.valueOf(userId));
                redisTemplate.opsForHash().increment(STAT_PREFIX + userId, FOLLOWING_FIELD, -1);
                redisTemplate.opsForHash().increment(STAT_PREFIX + authorId, FOLLOWERS_FIELD, -1);
            }
            long finalUserId = userId;
            long finalAuthorId = authorId;
            interactionExecutor.execute(() -> {
                try {
                    followRepository.cancelFollow(finalUserId, finalAuthorId);
                    followRepository.cancelFan(finalAuthorId, finalUserId);
                } catch (Exception ex) {
                    log.warn("取消关注异步落库失败（fail-open）: userId={}, authorId={}, {}",
                            finalUserId, finalAuthorId, ex.getMessage());
                }
            });
            return changed;
        } catch (Exception e) {
            log.warn("取消关注失败（fail-open）: userId={}, authorId={}, {}", userId, authorId, e.getMessage());
            return false;
        }
    }

    /**
     * 关注通知被关注者（ADR-0009，fail-open 单点生成）。
     *
     * <p>接收方 = 被关注者 {@code authorId}，发起方 = 关注者 {@code actorId}；自关跳过。
     * {@link NotificationGenerator#generate} 内部已异步 + 兜底，本方法再包一层 try 仅作双保险。</p>
     */
    private void notifyFollow(long actorId, long recipientId) {
        try {
            if (actorId == recipientId) {
                return; // 自关，不通知
            }
            String eventId = "FOLLOW:" + recipientId + ":" + actorId;
            notificationGenerator.generate(new NotificationEvent(eventId, NotificationType.FOLLOW,
                    recipientId, actorId, "USER", String.valueOf(actorId), "关注了你", null, Instant.now()));
        } catch (Exception e) {
            log.warn("关注通知生成失败（fail-open，不影响主流程）: actorId={}, recipientId={}, {}",
                    actorId, recipientId, e.getMessage());
        }
    }

    /** 我是否关注了 authorId。 */
    public boolean isFollowing(long userId, long authorId) {
        try {
            return redisTemplate.opsForZSet().score(FOLLOW_PREFIX + userId, String.valueOf(authorId)) != null;
        } catch (Exception e) {
            log.warn("关注态读取失败（按未关注处理）: userId={}, authorId={}, {}", userId, authorId, e.getMessage());
            return false;
        }
    }

    /** authorId 是否关注了我（即 authorId 在我粉丝集合里）。 */
    public boolean isFollower(long userId, long authorId) {
        try {
            return redisTemplate.opsForZSet().score(FOLLOWER_PREFIX + userId, String.valueOf(authorId)) != null;
        } catch (Exception e) {
            log.warn("粉丝态读取失败（按非粉丝处理）: userId={}, authorId={}, {}", userId, authorId, e.getMessage());
            return false;
        }
    }

    /** 我的关注列表（按关注时刻倒序分页）。cursor=0 表示首页；后续传上次返回的 nextCursor。 */
    public FollowPage following(long userId, long cursor, int limit) {
        return page(FOLLOW_PREFIX + userId, cursor, limit);
    }

    /** 我的粉丝列表（按关注时刻倒序分页）。 */
    public FollowPage followers(long userId, long cursor, int limit) {
        return page(FOLLOWER_PREFIX + userId, cursor, limit);
    }

    /** 某用户社交计数（following / followers）。字段缺失返回 0。 */
    public long stat(long userId, String field) {
        try {
            Object v = redisTemplate.opsForHash().get(STAT_PREFIX + userId, field);
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            log.warn("社交计数读取失败（按 0 处理）: userId={}, field={}", userId, field);
            return 0L;
        }
    }

    /**
     * 关注推荐（抖音式二阶关注）：取我的关注列表前若干，合并他们的关注，排除自己+已关注，
     * 按「二阶亲近度」（被共同关注数）降序截断返回候选 userId。fail-open：异常返回空。
     */
    public List<String> recommend(long userId, int limit) {
        try {
            Set<String> mine = redisTemplate.opsForZSet().reverseRange(FOLLOW_PREFIX + userId, 0, 20);
            if (mine == null || mine.isEmpty()) {
                return List.of();
            }
            Set<String> excluded = new HashSet<>(mine);
            excluded.add(String.valueOf(userId));
            Map<String, Long> candidateScore = new HashMap<>();
            for (String fid : mine) {
                Set<String> theirFollows = redisTemplate.opsForZSet().reverseRange(FOLLOW_PREFIX + fid, 0, 50);
                if (theirFollows == null) {
                    continue;
                }
                for (String cand : theirFollows) {
                    if (excluded.contains(cand)) {
                        continue;
                    }
                    candidateScore.merge(cand, 1L, Long::sum);
                }
            }
            List<String> ranked = candidateScore.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
            return ranked;
        } catch (Exception e) {
            log.warn("关注推荐失败（返回空）: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 DB 重建某用户的 Redis 社交图（关注/粉丝/计数）。管理端点调用，用于 Redis 故障/重启后恢复。
     * 双写已保证 Redis 实时一致，本方法仅作兜底重建。fail-open：异常只告警。
     */
    public void rebuildUserGraph(long userId) {
        try {
            List<Long> follows = followRepository.findFollows(userId);
            long now = System.currentTimeMillis();
            redisTemplate.delete(FOLLOW_PREFIX + userId);
            for (Long authorId : follows) {
                redisTemplate.opsForZSet().add(FOLLOW_PREFIX + userId, String.valueOf(authorId), now);
                redisTemplate.opsForZSet().add(FOLLOWER_PREFIX + authorId, String.valueOf(userId), now);
            }
            List<Long> fans = followRepository.findFans(userId);
            redisTemplate.delete(FOLLOWER_PREFIX + userId);
            for (Long fanId : fans) {
                redisTemplate.opsForZSet().add(FOLLOWER_PREFIX + userId, String.valueOf(fanId), now);
                redisTemplate.opsForZSet().add(FOLLOW_PREFIX + fanId, String.valueOf(userId), now);
            }
            redisTemplate.opsForHash().put(STAT_PREFIX + userId, FOLLOWING_FIELD, (long) follows.size());
            redisTemplate.opsForHash().put(STAT_PREFIX + userId, FOLLOWERS_FIELD, (long) fans.size());
            log.info("关注图重建完成: userId={}, following={}, followers={}", userId, follows.size(), fans.size());
        } catch (Exception e) {
            log.warn("关注图重建失败: userId={}, {}", userId, e.getMessage());
        }
    }

    /** 关注/粉丝列表分页结果（items = userId 字符串，nextCursor=0 表示无更多）。 */
    public record FollowPage(List<String> items, long nextCursor) {
    }

    /** ZSET 按 score 倒序分页：max = cursor-1（排它，跳过上一页末位），首页 max=+inf。 */
    private FollowPage page(String key, long cursor, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        try {
            double max = cursor <= 0 ? Double.POSITIVE_INFINITY : (cursor - 1.0);
            Set<String> set = redisTemplate.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, n);
            List<String> items = set == null ? List.of() : new ArrayList<>(set);
            long next = 0L;
            if (items.size() == n) {
                Double lastScore = redisTemplate.opsForZSet().score(key, items.get(items.size() - 1));
                next = lastScore == null ? 0L : lastScore.longValue();
            }
            return new FollowPage(items, next);
        } catch (Exception e) {
            log.warn("关注列表分页读取失败（返回空）: key={}, {}", key, e.getMessage());
            return new FollowPage(List.of(), 0L);
        }
    }
}
