package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 评论点赞服务（抖音式 toggle：per-user 去重 + 已赞态 + 计数 + 接 tf-counter）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>单一事实源 = Redis Set（热键 64 槽打散）</b>：每条评论的去重集合由单 Key
 *       {@code tf:comment:liked:{commentId}} 拆为 {@code tf:comment:liked:{commentId}:{slot}}
 *       （slot 数 64，与 media 点赞 / counter bucket-count 对齐）。{@code slot = floorMod(hashCode(userId), 64)}。
 *       目的：爆款评论（十万赞）单 Key 的 {@code SCARD/SADD/SISMEMBER} 与带宽集中到一台 Redis
 *       节点会成为热键瓶颈；打散后读扩散到 64 个 Key、写按用户均匀分布，消除单点热键。
 *       {@code SADD} 返回 1 才是「新赞」（原子去重），0 表示已赞——以此实现抖音式「再点取消」幂等 toggle。</li>
 *   <li><b>计数聚合</b>：{@code likeCount} 经 pipeline 并发 {@code SCARD} 64 槽求和，一次往返；
 *       去重语义天然成立（同一用户只落在一个槽）。批量 {@link #likeCounts(Collection)} 用于列表页
 *       N 条评论一次 pipeline 聚合，避免 N 次来回。</li>
 *   <li><b>接 tf-counter（opt-in）</b>：每次<b>实际状态变化</b>（新增赞 / 取消赞）才经
 *       {@link CounterEventPublisher} 发一条 {@code COMMENT_LIKE ±1} 增量给 tf-counter 聚合底座
 *       （由其批量落 {@code post_counter}）。该发布器由 {@code turbofeed.counter.service.enabled}
 *       闸控——默认关，关时 Bean 不创建、零回归；开启后 fail-open，投递失败仅告警。</li>
 *   <li><b>持久化兜底（异步落库）</b>：状态变化后把 {@code comment_like} 的 INSERT/DELETE 提交到
 *       {@code interactionExecutor} 异步刷盘，主链路不阻塞 DB 写；Redis 仍是权威计数源。
 *       内部 fail-open：落库异常只告警，不影响主流程。<b>最终一致取舍</b>：同一用户对同评论
 *       「点赞→立即取消」极端竞态下，异步队列可能先 DELETE 后 INSERT，产生一行孤儿备份记录；
 *       因计数来自 Redis，对用户不可见，属可接受的备份最终一致。</li>
 *   <li><b>fail-open</b>：点赞是互动优化项，Redis/MQ 组件任一异常按既定策略兜底，绝不把失败抛给用户主流程。</li>
 * </ul>
 */
@Service
public class CommentLikeService {

    private static final Logger log = LoggerFactory.getLogger(CommentLikeService.class);

    /** 点赞去重集合前缀：{@code tf:comment:liked:{commentId}:{slot} => Set<userId>}。 */
    static final String LIKED_KEY_PREFIX = "tf:comment:liked:";
    /** 热键打散槽数：与 counter / media 点赞 bucket-count=64 对齐，读扩散到 64 个 Key。 */
    private static final int SLOT_COUNT = 64;
    /** 去重集合 TTL：略长于内容公域生命周期，内容下架后自然过期。 */
    private static final Duration SET_TTL = Duration.ofDays(8);

    private final StringRedisTemplate redisTemplate;
    private final CommentLikeJdbcRepository likeRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    /** 计数增量发布器（opt-in）：{@code turbofeed.counter.service.enabled=false} 时工厂不提供 Bean，恒为空。 */
    private final ObjectProvider<CounterEventPublisher> counterPublisher;
    /** 异步落库执行器（与 ② 点赞同款 interactionExecutor：队列 1024、CallerRunsPolicy 背压）。 */
    @Autowired
    @Qualifier("interactionExecutor")
    private Executor interactionExecutor;

    public CommentLikeService(StringRedisTemplate redisTemplate, CommentLikeJdbcRepository likeRepository,
                              SnowflakeIdGenerator snowflakeIdGenerator,
                              ObjectProvider<CounterEventPublisher> counterPublisher) {
        this.redisTemplate = redisTemplate;
        this.likeRepository = likeRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.counterPublisher = counterPublisher;
    }

    /** 用户归属的槽号（与分片/计数桶同套散列，保证同一用户始终映射到同一槽）。 */
    private static int slotOf(long userId) {
        return Math.floorMod(Long.hashCode(userId), SLOT_COUNT);
    }

    /** 某评论某槽的去重集合 Key。 */
    private static String slotKey(String commentId, int slot) {
        return LIKED_KEY_PREFIX + commentId + ":" + slot;
    }

    /**
     * 切换点赞状态（抖音式 toggle）。
     *
     * <p>入口限流由 {@link com.turbofeed.gateway.service.comment.CommentService#like} 统一负责，
     * 本方法聚焦 Redis 去重 + 计数 + 落库 + 通知。</p>
     *
     * @param mediaId   分片键（落库/删单分片命中需要）
     * @param commentId 被赞评论 ID
     * @param userId    点赞者（来自 JWT）
     * @return 操作后的「是否已赞」态（兜底返回查询态）
     */
    public boolean toggle(String mediaId, long commentId, long userId) {
        String uid = String.valueOf(userId);
        String key = slotKey(String.valueOf(commentId), slotOf(userId));
        try {
            Long added = redisTemplate.opsForSet().add(key, uid);
            if (added != null && added == 1L) {
                redisTemplate.expire(key, SET_TTL);
                // 实际新增赞：通知 tf-counter 聚合底座（opt-in，fail-open）
                notifyCounter(String.valueOf(commentId), 1);
                // 异步落库（灾备），主链路不阻塞 DB 写
                interactionExecutor.execute(() -> {
                    try {
                        likeRepository.insert(new CommentLike(snowflakeIdGenerator.nextId(), commentId, userId, mediaId, Instant.now()));
                    } catch (Exception ex) {
                        log.warn("评论点赞异步落库失败（fail-open，不影响主流程）: commentId={}, userId={}, {}",
                                commentId, userId, ex.getMessage());
                    }
                });
                return true;
            }
            // 已赞 → toggle 取消赞（SADD 幂等返回 0 即视为已赞）
            Long removed = redisTemplate.opsForSet().remove(key, uid);
            if (removed != null && removed == 1L) {
                notifyCounter(String.valueOf(commentId), -1);
                interactionExecutor.execute(() -> {
                    try {
                        likeRepository.delete(commentId, userId, mediaId);
                    } catch (Exception ex) {
                        log.warn("评论取消赞异步落库失败（fail-open，不影响主流程）: commentId={}, userId={}, {}",
                                commentId, userId, ex.getMessage());
                    }
                });
            }
            return false;
        } catch (Exception e) {
            log.warn("评论点赞状态变更失败（fail-open，按查询态兜底）: commentId={}, userId={}, {}",
                    commentId, userId, e.getMessage());
            return isLiked(String.valueOf(commentId), userId);
        }
    }

    /** 用户是否已赞该评论（仅查其归属槽，O(1)）。 */
    public boolean isLiked(String commentId, long userId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(slotKey(commentId, slotOf(userId)), String.valueOf(userId)));
        } catch (Exception e) {
            log.warn("评论已赞态读取失败（按未赞处理）: commentId={}, userId={}, {}", commentId, userId, e.getMessage());
            return false;
        }
    }

    /** 单条评论点赞数（pipeline 并发 SCARD 64 槽求和，一次往返）。 */
    public long likeCount(String commentId) {
        try {
            List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    connection.sCard(slotKey(commentId, slot).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            return sumSizes(sizes);
        } catch (Exception e) {
            log.warn("评论点赞数聚合读取失败（按 0 处理）: commentId={}, {}", commentId, e.getMessage());
            return 0L;
        }
    }

    /** 批量点赞数（列表页 N 条评论一次 pipeline 聚合，避免 N 次来回）。 */
    public Map<Long, Long> likeCounts(Collection<Long> commentIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (commentIds.isEmpty()) {
            return result;
        }
        try {
            List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long cid : commentIds) {
                    for (int slot = 0; slot < SLOT_COUNT; slot++) {
                        connection.sCard(slotKey(String.valueOf(cid), slot).getBytes(StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
            int i = 0;
            for (Long cid : commentIds) {
                long sum = 0L;
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    sum += sizeOf(sizes.get(i++));
                }
                result.put(cid, sum);
            }
        } catch (Exception e) {
            log.warn("评论批量点赞数聚合读取失败（按 DB 冗余值兜底）: {}", e.getMessage());
        }
        return result;
    }

    private static long sumSizes(List<Object> sizes) {
        long total = 0L;
        for (Object o : sizes) {
            total += sizeOf(o);
        }
        return total;
    }

    private static long sizeOf(Object o) {
        if (o instanceof Long l) {
            return l;
        } else if (o instanceof Integer i) {
            return i;
        }
        return 0L;
    }

    /** 发计数增量给 tf-counter 聚合底座（opt-in）。fail-open：失败仅告警，不影响点赞主流程。 */
    private void notifyCounter(String commentId, int delta) {
        try {
            counterPublisher.ifAvailable(c -> c.incr(commentId, "COMMENT_LIKE", delta));
        } catch (Exception e) {
            log.warn("评论计数增量通知 tf-counter 失败（fail-open，不影响主流程）: commentId={}, delta={}, {}",
                    commentId, delta, e.getMessage());
        }
    }
}
