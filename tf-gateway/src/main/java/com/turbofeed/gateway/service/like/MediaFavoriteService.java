package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.LikeCountScript;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 视频收藏服务（抖音式：per-user 去重 + 已收藏态 + 计数 + 接赛马 + 我的收藏列表）。
 *
 * <p>与 {@link MediaLikeService} 完全镜像的实现路径，仅把「赞」语义替换为「收藏」：</p>
 * <ul>
 *   <li><b>单一事实源 = Redis Set（热键 64 槽打散）</b>：{@code tf:favorite:users:{postId}:{slot}}
 *       （{@code slot = floorMod(hashCode(userId), 64)}）。{@code SADD} 返回 1 才是「新收藏」
 *       （原子去重），0 表示已收藏（抖音式「再点取消」幂等）。</li>
 *   <li><b>计数聚合</b>：单 Key {@code tf:favorite:count:{postId}}（复用 {@link LikeCountScript#COUNT_BUMP}
 *       Lua，Cluster 安全），读 O(1)；缺失时回退 64 槽 {@code SCARD} 求和并写回预热。</li>
 *   <li><b>我的收藏列表</b>：额外维护 per-user ZSET {@code tf:favorite:user:{userId}}
 *       （score = 收藏时刻 ms），由 {@link #myFavorites(long, long, int)} 游标分页读取
 *       （与 {@code FollowService#page} 同款 {@code reverseRangeByScore} 倒序游标）。</li>
 *   <li><b>接推荐赛马</b>：每次<b>实际状态变化</b>才经 {@link BehaviorEventPublisher}
 *       发一条 {@code FAVORITE} / {@code UNFAVORITE} 行为事件给引擎——引擎 {@code PostStatService}
 *       据此累加/回退实时分与热点榜权重，{@code InterestService} 据此累加到用户画像
 *       （权重 1.5，属强意图，同 SHARE/COMMENT）。</li>
 *   <li><b>持久聚合底座（opt-in）</b>：实际状态变化再经 {@link CounterEventPublisher}
 *       发收藏增量给 tf-counter（{@code turbofeed.counter.service.enabled} 闸控，默认关）。</li>
 *   <li><b>持久化兜底（异步落库）</b>：状态变化后把 {@code media_favorite} 的 INSERT/DELETE 提交到
 *       {@code interactionExecutor} 异步刷盘；Redis 仍是权威计数源，落库仅为灾备重建用。fail-open。</li>
 *   <li><b>用户维度限流</b>：入口经 {@link UploadRateLimiter#tryAcquire(String, String)}
 *       （前缀 {@code rl:favorite:}）。</li>
 *   <li><b>fail-open</b>：收藏是互动优化项，Redis/引擎/MQ/限流组件任一异常按既定策略兜底，
 *       绝不把「收藏」的失败抛给用户主流程。</li>
 * </ul>
 */
@Service
public class MediaFavoriteService {

    private static final Logger log = LoggerFactory.getLogger(MediaFavoriteService.class);

    /** 收藏去重集合前缀：{@code tf:favorite:users:{postId}:{slot} => Set<userId>}。 */
    static final String USERS_KEY_PREFIX = "tf:favorite:users:";
    /**
     * 收藏计数 Key 前缀：{@code tf:favorite:count:{postId} => 整数}（复用媒体点赞计数 Key 同款 Lua）。
     */
    static final String COUNT_KEY_PREFIX = "tf:favorite:count:";
    /** 收藏用户维度限流 key 前缀（与上传 rl:upload:、点赞 rl:like: 隔离计数，共用一套阈值配置）。 */
    private static final String RL_FAVORITE_PREFIX = "rl:favorite:";
    /** 我的收藏列表 ZSET 前缀：{@code tf:favorite:user:{userId} => ZSet<postId, score=收藏时刻ms>}。 */
    static final String USER_FAV_KEY_PREFIX = "tf:favorite:user:";
    /** 热键打散槽数：与媒体点赞/counter bucket-count=64 对齐。 */
    private static final int SLOT_COUNT = 64;
    /** 去重集合 TTL：略长于内容公域生命周期（与媒体点赞对齐）。 */
    private static final Duration SET_TTL = Duration.ofDays(8);
    /** 计数 Key TTL（与去重集合解耦，取更长）。 */
    private static final Duration COUNT_TTL = Duration.ofDays(30);
    /** 我的收藏列表 ZSET TTL：用户维度的收藏视图，取较长以免列表「凭空消失」；写时续期。 */
    private static final Duration USER_FAV_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final BehaviorEventPublisher behaviorPublisher;
    private final MediaFavoriteJdbcRepository favoriteRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Executor interactionExecutor;
    private final UploadRateLimiter rateLimiter;
    /** 计数增量发布器（opt-in）：{@code turbofeed.counter.service.enabled=false} 时工厂不提供 Bean，恒为空。 */
    private final ObjectProvider<CounterEventPublisher> counterPublisher;

    public MediaFavoriteService(StringRedisTemplate redisTemplate, BehaviorEventPublisher behaviorPublisher,
                                MediaFavoriteJdbcRepository favoriteRepository, SnowflakeIdGenerator snowflakeIdGenerator,
                                @Qualifier("interactionExecutor") Executor interactionExecutor,
                                UploadRateLimiter rateLimiter,
                                ObjectProvider<CounterEventPublisher> counterPublisher) {
        this.redisTemplate = redisTemplate;
        this.behaviorPublisher = behaviorPublisher;
        this.favoriteRepository = favoriteRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.interactionExecutor = interactionExecutor;
        this.rateLimiter = rateLimiter;
        this.counterPublisher = counterPublisher;
    }

    /** 用户归属的槽号（与分片/计数桶同套散列，保证同一用户始终映射到同一槽）。 */
    private static int slotOf(long userId) {
        return Math.floorMod(Long.hashCode(userId), SLOT_COUNT);
    }

    /** 某帖某槽的去重集合 Key。 */
    private static String slotKey(String postId, int slot) {
        return USERS_KEY_PREFIX + postId + ":" + slot;
    }

    /** 我的收藏列表 ZSET Key。 */
    private static String userFavKey(long userId) {
        return USER_FAV_KEY_PREFIX + userId;
    }

    /**
     * 去重集合已不可信时，用灾备表确认「此前是否收藏过」（fail-open）。查不动就按新收藏放行。
     */
    private boolean alreadyFavoritedInDb(String postId, long userId) {
        try {
            return favoriteRepository.existsFavorite(postId, userId);
        } catch (Exception e) {
            log.warn("收藏去重兜底查询失败（按新收藏放行）: postId={}, userId={}, {}", postId, userId, e.getMessage());
            return false;
        }
    }

    /**
     * 切换收藏状态（抖音式 toggle）。
     *
     * @param postId    被收藏内容身份（{@code postId} = 时间线幂等键；也是引擎统计/热点榜的键）
     * @param userId    收藏者（来自 JWT）
     * @param favorite  {@code true}=收藏，{@code false}=取消收藏
     * @return 操作后的「是否已收藏」态（兜底返回请求态）
     * @throws BizException 用户维度限流触发时抛 {@code RATE_LIMITED}
     */
    public boolean apply(String postId, long userId, boolean favorite) {
        // ① 用户维度限流：单用户刷收藏/狂点直接拒绝（与上传/点赞共用阈值配置，前缀隔离计数）
        if (!rateLimiter.tryAcquire(String.valueOf(userId), RL_FAVORITE_PREFIX)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "收藏过于频繁，请稍后再试");
        }
        String uid = String.valueOf(userId);
        String key = slotKey(postId, slotOf(userId));
        try {
            if (favorite) {
                Boolean slotExisted = redisTemplate.hasKey(key);
                Long added = redisTemplate.opsForSet().add(key, uid);
                if (added != null && added == 1L) {
                    redisTemplate.expire(key, SET_TTL);
                    if (!Boolean.TRUE.equals(slotExisted) && alreadyFavoritedInDb(postId, userId)) {
                        // 去重集合已被回收，但灾备表证明该用户此前收藏过 → 判为「重复收藏」：
                        // 成员已由上面的 SADD 恢复（收藏态正确），但不重复计数、不重复发增量、不重复落库。
                        log.info("收藏去重集合已回收，据灾备表判定为重复收藏（不计入计数）: postId={}, userId={}",
                                postId, userId);
                        addToUserFav(userId, postId);
                        return true;
                    }
                    // 与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                    bumpCount(postId, 1);
                    publish(postId, userId, "FAVORITE");
                    // ② 持久聚合底座（opt-in）：实际新增收藏才发 +1 增量
                    notifyCounter(postId, 1);
                    // ③ 维护我的收藏列表 ZSET（收藏态视图，score=收藏时刻ms）
                    addToUserFav(userId, postId);
                    // ④ 异步落库（灾备），主链路不阻塞 DB 写
                    interactionExecutor.execute(() -> {
                        try {
                            favoriteRepository.insert(new MediaFavorite(snowflakeIdGenerator.nextId(), postId, userId, Instant.now()));
                        } catch (Exception ex) {
                            log.warn("收藏异步落库失败（fail-open，不影响主流程）: postId={}, userId={}, {}",
                                    postId, userId, ex.getMessage());
                        }
                    });
                    return true;
                }
                addToUserFav(userId, postId);
                return true; // 已收藏，幂等
            } else {
                Long removed = redisTemplate.opsForSet().remove(key, uid);
                if (removed != null && removed == 1L) {
                    // 与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                    bumpCount(postId, -1);
                    publish(postId, userId, "UNFAVORITE");
                    // ② 持久聚合底座（opt-in）：实际取消收藏才发 -1 增量
                    notifyCounter(postId, -1);
                    // ③ 维护我的收藏列表 ZSET
                    removeFromUserFav(userId, postId);
                    // ④ 异步落库（灾备）
                    interactionExecutor.execute(() -> {
                        try {
                            favoriteRepository.delete(postId, userId);
                        } catch (Exception ex) {
                            log.warn("取消收藏异步落库失败（fail-open，不影响主流程）: postId={}, userId={}, {}",
                                    postId, userId, ex.getMessage());
                        }
                    });
                    return false;
                }
                removeFromUserFav(userId, postId);
                return false; // 本就未收藏，幂等
            }
        } catch (Exception e) {
            log.warn("收藏状态变更失败（fail-open，按请求态兜底）: postId={}, userId={}, favorite={}, {}",
                    postId, userId, favorite, e.getMessage());
            return favorite;
        }
    }

    /** 用户是否已收藏该内容（仅查其归属槽，O(1)）。 */
    public boolean isFavorited(String postId, long userId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(slotKey(postId, slotOf(userId)), String.valueOf(userId)));
        } catch (Exception e) {
            log.warn("已收藏态读取失败（按未收藏处理）: postId={}, userId={}, {}", postId, userId, e.getMessage());
            return false;
        }
    }

    /**
     * 批量已收藏态（一次 pipeline {@code SISMEMBER} 取 N 条）。
     *
     * @return postId → 是否已收藏；<b>只包含已收藏的项</b>（未收藏与读取失败均不出现，调用方按未收藏兜底）
     */
    public Map<String, Boolean> isFavoritedBatch(Collection<String> postIds, long userId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        List<String> ids = new ArrayList<>(postIds);
        String uid = String.valueOf(userId);
        try {
            List<Object> members = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String id : ids) {
                    connection.setCommands().sIsMember(
                            slotKey(id, slotOf(userId)).getBytes(StandardCharsets.UTF_8),
                            uid.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            for (int i = 0; i < ids.size(); i++) {
                if (Boolean.TRUE.equals(members.get(i))) {
                    result.put(ids.get(i), Boolean.TRUE);
                }
            }
        } catch (Exception e) {
            log.warn("批量已收藏态读取失败（按未收藏处理）: size={}, userId={}, {}",
                    ids.size(), userId, e.getMessage());
        }
        return result;
    }

    /**
     * 当前内容收藏数（单 Key {@code GET}，O(1)）；缺失回退 64 槽 {@code SCARD} 求和并写回预热。
     */
    public long favoriteCount(String postId) {
        try {
            String v = redisTemplate.opsForValue().get(countKey(postId));
            if (v != null) {
                return Long.parseLong(v);
            }
            long slots = sumSlotCardinalities(postId);
            trySetCount(postId, slots);
            return slots;
        } catch (Exception e) {
            log.warn("收藏数读取失败（回退 64 槽聚合）: postId={}, {}", postId, e.getMessage());
            try {
                return sumSlotCardinalities(postId);
            } catch (Exception e2) {
                log.warn("收藏数回退聚合亦失败（按 0 处理）: postId={}, {}", postId, e2.getMessage());
                return 0L;
            }
        }
    }

    /**
     * 批量收藏数（一次 {@code MGET} 取 N 个计数 Key）；冷启动项合并进一个 pipeline 回退。
     *
     * @param postIds 内容身份（时间线幂等键 {@code timelineKey}）集合
     * @return postId → 收藏数；读取失败的项不出现在结果里（调用方按 0 / DB 冗余值兜底）
     */
    public Map<String, Long> favoriteCounts(Collection<String> postIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        List<String> ids = new ArrayList<>(postIds);
        try {
            List<String> countKeys = new ArrayList<>(ids.size());
            for (String id : ids) {
                countKeys.add(countKey(id));
            }
            List<String> vals = redisTemplate.opsForValue().multiGet(countKeys);
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String v = (vals == null || i >= vals.size()) ? null : vals.get(i);
                if (v != null) {
                    result.put(ids.get(i), Long.parseLong(v));
                } else {
                    missing.add(ids.get(i));
                }
            }
            if (!missing.isEmpty()) {
                List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (String id : missing) {
                        for (int slot = 0; slot < SLOT_COUNT; slot++) {
                            connection.sCard(slotKey(id, slot).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    return null;
                });
                List<String> warmed = new ArrayList<>(missing.size());
                List<Long> warmValues = new ArrayList<>(missing.size());
                for (int idx = 0; idx < missing.size(); idx++) {
                    long sum = 0L;
                    for (int slot = 0; slot < SLOT_COUNT; slot++) {
                        sum += sizeOf(sizes.get(idx * SLOT_COUNT + slot));
                    }
                    String id = missing.get(idx);
                    result.put(id, sum);
                    warmed.add(id);
                    warmValues.add(sum);
                }
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (int idx = 0; idx < warmed.size(); idx++) {
                        String id = warmed.get(idx);
                        for (int slot = 0; slot < SLOT_COUNT; slot++) {
                            connection.keyCommands().expire(
                                    slotKey(id, slot).getBytes(StandardCharsets.UTF_8), SET_TTL.getSeconds());
                        }
                        connection.stringCommands().set(
                                countKey(id).getBytes(StandardCharsets.UTF_8),
                                String.valueOf(warmValues.get(idx)).getBytes(StandardCharsets.UTF_8),
                                Expiration.from(COUNT_TTL),
                                RedisStringCommands.SetOption.SET_IF_ABSENT);
                    }
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("批量收藏数读取失败（调用方按 DB 冗余值兜底）: size={}, {}", ids.size(), e.getMessage());
        }
        return result;
    }

    /**
     * 我的收藏列表（per-user ZSET，score=收藏时刻ms，倒序游标分页）。
     *
     * <p>与 {@code FollowService#page} 同款语义：{@code max = cursor-1}（排它，跳过上一页末位），
     * 首页 {@code max=+inf}；返回 {@code items}（postId 列表）与 {@code nextCursor}
     * （= 当页末位 score，无更多时为 0）。fail-open：异常返回空列表。</p>
     *
     * @param userId  收藏者
     * @param cursor  游标（上一次返回的 nextCursor，首页传 0）
     * @param limit   每页条数（1~100，超出夹紧）
     * @return 本页 postId 列表 + 下一页游标
     */
    public FavoriteList myFavorites(long userId, long cursor, int limit) {
        String key = userFavKey(userId);
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
            return new FavoriteList(items, next);
        } catch (Exception e) {
            log.warn("我的收藏列表读取失败（返回空）: userId={}, {}", userId, e.getMessage());
            return new FavoriteList(List.of(), 0L);
        }
    }

    /**
     * 显式对账：用 64 槽去重集合（幂等权威）重算并<b>覆盖</b>计数 Key。
     *
     * @return 校正后的收藏数（= 64 槽基数之和）
     */
    public long recountFromSlots(String postId) {
        long slots = sumSlotCardinalities(postId);
        try {
            redisTemplate.opsForValue().set(countKey(postId), String.valueOf(slots), COUNT_TTL);
        } catch (Exception e) {
            log.warn("收藏计数对账写回失败: postId={}, {}", postId, e.getMessage());
        }
        return slots;
    }

    /** 计数 Key（单 Key，不打散）。 */
    private static String countKey(String postId) {
        return COUNT_KEY_PREFIX + postId;
    }

    /**
     * 计数 Key 增量维护（复用媒体点赞同款单 Key Lua）。
     */
    private void bumpCount(String postId, int delta) {
        String key = countKey(postId);
        try {
            Long v = redisTemplate.execute(LikeCountScript.COUNT_BUMP, List.of(key),
                    String.valueOf(delta), String.valueOf(COUNT_TTL.toMillis()));
            if (v != null) {
                return; // 热路径：Key 原本存在，已完成 INCRBY + PEXPIRE
            }
            long slots = sumSlotCardinalities(postId);
            if (trySetCount(postId, slots)) {
                log.info("收藏计数冷启动校正（计数 Key 缺失，按 64 槽去重集合重算）: postId={}, corrected={}",
                        postId, slots);
            } else {
                redisTemplate.opsForValue().increment(key, delta);
            }
        } catch (Exception e) {
            log.warn("收藏计数 Key 维护失败（fail-open，读路径回退 64 槽）: postId={}, delta={}, {}",
                    postId, delta, e.getMessage());
        }
    }

    /** 冷启动预热：仅当计数 Key 仍不存在时写入（{@code SETNX} 语义）。 */
    private boolean trySetCount(String postId, long value) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(countKey(postId), String.valueOf(value), COUNT_TTL));
        } catch (Exception ignore) {
            return false;
        }
    }

    /** 维护我的收藏列表 ZSET（加成员 + 续期）。fail-open。 */
    private void addToUserFav(long userId, String postId) {
        try {
            String key = userFavKey(userId);
            redisTemplate.opsForZSet().add(key, postId, (double) System.currentTimeMillis());
            redisTemplate.expire(key, USER_FAV_TTL);
        } catch (Exception e) {
            log.warn("我的收藏列表 ZSET 写入失败（fail-open，不影响主流程）: userId={}, postId={}, {}",
                    userId, postId, e.getMessage());
        }
    }

    /** 维护我的收藏列表 ZSET（移除成员）。fail-open。 */
    private void removeFromUserFav(long userId, String postId) {
        try {
            redisTemplate.opsForZSet().remove(userFavKey(userId), postId);
        } catch (Exception e) {
            log.warn("我的收藏列表 ZSET 移除失败（fail-open，不影响主流程）: userId={}, postId={}, {}",
                    userId, postId, e.getMessage());
        }
    }

    /**
     * 灾备重建：从 {@code media_favorite} 表恢复去重集合与计数 Key（per-post 维度）。
     *
     * @return 重建后的收藏数（= DB 中该内容的收藏行数）
     */
    public long rebuildFromDb(String postId) {
        List<Long> userIds = favoriteRepository.listUserIds(postId);
        if (userIds.isEmpty()) {
            try {
                redisTemplate.delete(countKey(postId));
            } catch (Exception ignore) {
                // 清理失败不影响返回
            }
            return 0L;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long uid : userIds) {
                    byte[] key = slotKey(postId, slotOf(uid)).getBytes(StandardCharsets.UTF_8);
                    connection.setCommands().sAdd(key, String.valueOf(uid).getBytes(StandardCharsets.UTF_8));
                    connection.keyCommands().expire(key, SET_TTL.getSeconds());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("收藏去重集合重建失败: postId={}, rows={}, {}", postId, userIds.size(), e.getMessage());
        }
        long count = userIds.size();
        try {
            redisTemplate.opsForValue().set(countKey(postId), String.valueOf(count), COUNT_TTL);
        } catch (Exception e) {
            log.warn("收藏计数 Key 重建写回失败: postId={}, {}", postId, e.getMessage());
        }
        log.info("收藏数据已从 DB 灾备重建: postId={}, 恢复收藏数={}", postId, count);
        return count;
    }

    /**
     * 灾备重建：从 {@code media_favorite} 表恢复某用户的「我的收藏」ZSET（user 维度）。
     *
     * @return 重建后的收藏内容数（= DB 中该用户的收藏行数）
     */
    public long rebuildUserFavorites(long userId) {
        List<String> postIds = favoriteRepository.listPostIds(userId);
        String key = userFavKey(userId);
        try {
            redisTemplate.delete(key);
            if (!postIds.isEmpty()) {
                for (String postId : postIds) {
                    // score 用当前时间（DB 无精确收藏时刻视图，重建后以「最近」排序，可接受）
                    redisTemplate.opsForZSet().add(key, postId, (double) System.currentTimeMillis());
                }
                redisTemplate.expire(key, USER_FAV_TTL);
            }
        } catch (Exception e) {
            log.warn("我的收藏列表 ZSET 重建失败: userId={}, rows={}, {}", userId, postIds.size(), e.getMessage());
        }
        log.info("我的收藏列表已从 DB 灾备重建: userId={}, 恢复收藏数={}", userId, postIds.size());
        return postIds.size();
    }

    /** 64 槽去重集合基数求和（幂等权威口径）：用于冷启动重建与对账。 */
    private long sumSlotCardinalities(String postId) {
        List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                connection.sCard(slotKey(postId, slot).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
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

    /** 发行为事件给引擎（接赛马：实时分 + 热点榜 + 用户画像）。fail-open：失败只告警。 */
    private void publish(String postId, long userId, String type) {
        try {
            behaviorPublisher.report(List.of(new BehaviorReport(postId, type, null, null, null, null)),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.warn("收藏行为事件投递引擎失败（fail-open，不影响主流程）: postId={}, type={}, {}",
                    postId, type, e.getMessage());
        }
    }

    /** 发计数增量给 tf-counter 聚合底座（opt-in）。fail-open：失败仅告警，不影响收藏主流程。 */
    private void notifyCounter(String postId, int delta) {
        try {
            counterPublisher.ifAvailable(c -> c.incr(postId, "FAVORITE", delta));
        } catch (Exception e) {
            log.warn("收藏计数增量通知 tf-counter 失败（fail-open，不影响主流程）: postId={}, delta={}, {}",
                    postId, delta, e.getMessage());
        }
    }

    /** 我的收藏列表分页结果（items = postId 字符串，nextCursor=0 表示无更多）。 */
    public record FavoriteList(List<String> postIds, long nextCursor) {
    }
}
