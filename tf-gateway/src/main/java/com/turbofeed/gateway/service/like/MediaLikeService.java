package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 视频点赞服务（抖音式：per-user 去重 + 已赞态 + 计数 + 接赛马）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>单一事实源 = Redis Set（热键 64 槽打散）</b>：每个内容的去重集合由单 Key
 *       {@code tf:like:users:{postId}} 拆为 {@code tf:like:users:{postId}:{slot}}（slot 数 64，
 *       与 counter {@code bucket-count} 对齐）。{@code slot = floorMod(hashCode(userId), 64)}。
 *       目的：爆款内容（百万赞）单 Key 的 {@code SCARD/SADD/SISMEMBER} 与带宽集中到一台 Redis
 *       节点会成为热键瓶颈；打散后读扩散到 64 个 Key、写按用户均匀分布，消除单点热键。
 *       {@code SADD} 返回 1 才是「新赞」（原子去重），0 表示已赞（抖音式「再点取消」幂等）。</li>
 *   <li><b>计数聚合</b>：{@code likeCount} 经 pipeline 并发 {@code SCARD} 64 槽求和，一次往返；
 *       去重语义天然成立（同一用户只落在一个槽）。</li>
 *   <li><b>接推荐赛马</b>：每次<b>实际状态变化</b>（新增赞 / 取消赞）才经 {@link BehaviorEventPublisher}
 *       发一条 {@code LIKE} / {@code UNLIKE} 行为事件给引擎——引擎 {@code PostStatService} 据此
 *       累加/回退实时分与热点榜权重。去重保证「一次状态变化 = 一条事件」，不漂移。</li>
 *   <li><b>持久化兜底（异步落库）</b>：状态变化后把 {@code media_like} 的 INSERT/DELETE 提交到
 *       {@code interactionExecutor}（队列 1024、CallerRunsPolicy 背压）异步刷盘，主链路不阻塞 DB 写；
 *       Redis 仍是权威计数源，落库仅为灾备重建用。内部 fail-open：落库异常只告警，不影响主流程。
 *       <b>最终一致取舍</b>：同一用户对同帖「点赞→立即取消」极端竞态下，异步队列可能先 DELETE 后 INSERT，
 *       产生一行孤儿备份记录；因计数来自 Redis，对用户不可见，属可接受的备份最终一致。</li>
 *   <li><b>用户维度限流</b>：入口经 {@link UploadRateLimiter#tryAcquire(String, String)}
 *       （前缀 {@code rl:like:}）复用上传同款 Redis 固定窗口限流，单用户刷赞/狂点直接
 *       {@code BizException(RATE_LIMITED)} 拒绝；与 Sentinel 机器维度限流互补。</li>
 *   <li><b>fail-open</b>：点赞是互动优化项，Redis/引擎/MQ/限流组件任一异常按既定策略兜底，
 *       绝不把「点个赞」的失败抛给用户主流程（与全局埋点语义一致）。</li>
 * </ul>
 */
@Service
public class MediaLikeService {

    private static final Logger log = LoggerFactory.getLogger(MediaLikeService.class);

    /** 点赞去重集合前缀：{@code tf:like:users:{postId}:{slot} => Set<userId>}。 */
    static final String USERS_KEY_PREFIX = "tf:like:users:";
    /** 点赞用户维度限流 key 前缀（与上传 rl:upload: 隔离计数，共用一套阈值配置）。 */
    private static final String RL_LIKE_PREFIX = "rl:like:";
    /** 热键打散槽数：与 counter bucket-count=64 对齐，读扩散到 64 个 Key、写按用户均匀分布。 */
    private static final int SLOT_COUNT = 64;
    /** 去重集合 TTL：略长于内容公域生命周期，内容下架后自然过期（与 tf:post:stat 对齐）。 */
    private static final Duration SET_TTL = Duration.ofDays(8);

    private final StringRedisTemplate redisTemplate;
    private final BehaviorEventPublisher behaviorPublisher;
    private final MediaLikeJdbcRepository likeRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Executor interactionExecutor;
    private final UploadRateLimiter rateLimiter;

    public MediaLikeService(StringRedisTemplate redisTemplate, BehaviorEventPublisher behaviorPublisher,
                            MediaLikeJdbcRepository likeRepository, SnowflakeIdGenerator snowflakeIdGenerator,
                            @Qualifier("interactionExecutor") Executor interactionExecutor,
                            UploadRateLimiter rateLimiter) {
        this.redisTemplate = redisTemplate;
        this.behaviorPublisher = behaviorPublisher;
        this.likeRepository = likeRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.interactionExecutor = interactionExecutor;
        this.rateLimiter = rateLimiter;
    }

    /** 用户归属的槽号（与分片/计数桶同套散列，保证同一用户始终映射到同一槽）。 */
    private static int slotOf(long userId) {
        return Math.floorMod(Long.hashCode(userId), SLOT_COUNT);
    }

    /** 某帖某槽的去重集合 Key。 */
    private static String slotKey(String postId, int slot) {
        return USERS_KEY_PREFIX + postId + ":" + slot;
    }

    /**
     * 切换点赞状态（抖音式 toggle）。
     *
     * @param postId 被赞内容身份（{@code postId} = 时间线幂等键；也是引擎统计/热点榜的键）
     * @param userId 点赞者（来自 JWT）
     * @param like   {@code true}=点赞，{@code false}=取消赞
     * @return 操作后的「是否已赞」态（兜底返回请求态）
     * @throws BizException 用户维度限流触发时抛 {@code RATE_LIMITED}
     */
    public boolean apply(String postId, long userId, boolean like) {
        // ① 用户维度限流：单用户刷赞/狂点直接拒绝（与上传共用阈值配置，前缀隔离计数）
        if (!rateLimiter.tryAcquire(String.valueOf(userId), RL_LIKE_PREFIX)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "点赞过于频繁，请稍后再试");
        }
        String uid = String.valueOf(userId);
        String key = slotKey(postId, slotOf(userId));
        try {
            if (like) {
                Long added = redisTemplate.opsForSet().add(key, uid);
                if (added != null && added == 1L) {
                    redisTemplate.expire(key, SET_TTL);
                    publish(postId, userId, "LIKE");
                    // ② 异步落库（灾备），主链路不阻塞 DB 写
                    interactionExecutor.execute(() -> {
                        try {
                            likeRepository.insert(new MediaLike(snowflakeIdGenerator.nextId(), postId, userId, Instant.now()));
                        } catch (Exception ex) {
                            log.warn("点赞异步落库失败（fail-open，不影响主流程）: postId={}, userId={}, {}",
                                    postId, userId, ex.getMessage());
                        }
                    });
                    return true;
                }
                return true; // 已赞，幂等
            } else {
                Long removed = redisTemplate.opsForSet().remove(key, uid);
                if (removed != null && removed == 1L) {
                    publish(postId, userId, "UNLIKE");
                    interactionExecutor.execute(() -> {
                        try {
                            likeRepository.delete(postId, userId);
                        } catch (Exception ex) {
                            log.warn("取消赞异步落库失败（fail-open，不影响主流程）: postId={}, userId={}, {}",
                                    postId, userId, ex.getMessage());
                        }
                    });
                    return false;
                }
                return false; // 本就未赞，幂等
            }
        } catch (Exception e) {
            log.warn("点赞状态变更失败（fail-open，按请求态兜底）: postId={}, userId={}, like={}, {}",
                    postId, userId, like, e.getMessage());
            return like;
        }
    }

    /** 当前用户是否已赞该内容（仅查其归属槽，O(1)）。 */
    public boolean isLiked(String postId, long userId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(slotKey(postId, slotOf(userId)), String.valueOf(userId)));
        } catch (Exception e) {
            log.warn("已赞态读取失败（按未赞处理）: postId={}, userId={}, {}", postId, userId, e.getMessage());
            return false;
        }
    }

    /** 当前内容点赞数（pipeline 并发 SCARD 64 槽求和，一次往返）。 */
    public long likeCount(String postId) {
        try {
            List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    connection.sCard(slotKey(postId, slot).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            long total = 0L;
            for (Object o : sizes) {
                if (o instanceof Long l) {
                    total += l;
                } else if (o instanceof Integer i) {
                    total += i;
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("点赞数聚合读取失败（按 0 处理）: postId={}, {}", postId, e.getMessage());
            return 0L;
        }
    }

    /** 发行为事件给引擎（接赛马：实时分 + 热点榜）。fail-open：失败只告警。 */
    private void publish(String postId, long userId, String type) {
        try {
            behaviorPublisher.report(List.of(new BehaviorReport(postId, type, null, null, null, null)),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.warn("点赞行为事件投递引擎失败（fail-open，不影响主流程）: postId={}, type={}, {}",
                    postId, type, e.getMessage());
        }
    }
}
