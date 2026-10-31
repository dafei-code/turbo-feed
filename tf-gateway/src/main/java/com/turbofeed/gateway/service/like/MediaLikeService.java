package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.gateway.service.notify.NotificationEvent;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.notify.NotificationType;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.LikeCountScript;
import com.turbofeed.gateway.util.MediaKeyUtil;
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
 *   <li><b>计数聚合（A1：独立计数 Key，读 O(1)）</b>：{@code likeCount} 走单 Key
 *       {@code tf:like:count:{postId}}（{@code INCR}/{@code DECR} 与去重集合同步变更）。
 *       <b>为什么计数 Key 不打散</b>：64 槽是为解决 Set <b>元素膨胀</b>导致的写热键（百万赞 =
 *       百万元素挤在一个 Key，SADD/SREM/带宽集中单节点）；而计数 Key 只存一个整数，
 *       {@code INCR} 恒为 O(1)、无膨胀，打散它只会把「读一次点赞数」放大成 64 次
 *       {@code SCARD}（改造前列表页 20 条 = 1280 个 Redis 命令），纯属负收益。</li>
 *   <li><b>平滑迁移与对账</b>：计数 Key 缺失（历史数据 / Key 过期 / Redis 重建）时，
 *       {@link #likeCount(String)} 回退 64 槽 {@code SCARD} 求和并写回预热；
 *       {@link #recountFromSlots(String)} 提供显式对账入口，用去重集合（幂等权威）校正
 *       计数 Key 因异常产生的漂移。</li>
 *   <li><b>接推荐赛马</b>：每次<b>实际状态变化</b>（新增赞 / 取消赞）才经 {@link BehaviorEventPublisher}
 *       发一条 {@code LIKE} / {@code UNLIKE} 行为事件给引擎——引擎 {@code PostStatService} 据此
 *       累加/回退实时分与热点榜权重。去重保证「一次状态变化 = 一条事件」，不漂移。</li>
 *   <li><b>持久聚合底座（P1-2，opt-in）</b>：实际状态变化再经 {@link CounterEventPublisher}
 *       发一条点赞增量给 tf-counter（聚合底座），由其批量落 {@code post_counter}。该发布器
 *       由 {@code turbofeed.counter.service.enabled} 闸控——默认关，关时 Bean 不创建、零回归；
 *       开启后 fail-open，投递失败仅告警，绝不阻断点赞主流程。与 Redis 实时计数解耦，
 *       不触碰 {@code media_like} 落库与 behavior-event 链路。</li>
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
    /**
     * 点赞计数 Key 前缀：{@code tf:like:count:{postId} => 整数}（A1）。
     * 单 Key 不打散——只存整数，{@code INCR} 恒 O(1) 且无元素膨胀，不存在 Set 那样的写热键问题。
     */
    static final String COUNT_KEY_PREFIX = "tf:like:count:";
    /** 点赞用户维度限流 key 前缀（与上传 rl:upload: 隔离计数，共用一套阈值配置）。 */
    private static final String RL_LIKE_PREFIX = "rl:like:";
    /** 热键打散槽数：与 counter bucket-count=64 对齐，读扩散到 64 个 Key、写按用户均匀分布。 */
    private static final int SLOT_COUNT = 64;
    /**
     * 去重集合 TTL：略长于内容公域生命周期，内容下架后自然过期（与 tf:post:stat 对齐）。
     *
     * <p><b>A4 滑动续期</b>：<b>单条</b>读路径（{@link #likeCount(String)}，即内容详情页）会用一个
     * pipeline 刷新全部 64 槽的 TTL，因此<b>仍在被浏览的内容永不过期</b>；只有连续 8 天无人打开
     * 详情页也无人点赞的内容才回收。这修掉了「固定 8 天到期 → 幂等语义失效」的正确性缺口：
     * 老内容被 {@code FeedPoolPromoter} 重新推回流量池后，用户可重复点赞、计数虚增、红心消失。
     * 原实现只在 {@code SADD} 成功时续期<b>被触碰的那一个槽</b>，活跃用户的槽活着、沉默用户的槽
     * 到期，同样会导致重复点赞。</p>
     *
     * <p><b>列表批量路径（{@link #likeCounts}）刻意不续期</b>：那里 QPS 最高，为续期再加 N×64 个
     * {@code EXPIRE} 会把 A1 省下的开销又还回去。详情页续期已足够覆盖「内容仍活跃」的判定。</p>
     *
     * <p><b>滑动续期也覆盖不到的窗口，由灾备表兜底（本次补齐）</b>：连续 8 天无人打开详情页
     * 也无人点赞的内容，64 槽仍会到期；此后曾点赞的用户再点一次，{@code SADD} 返回 1 会被
     * 误判为「新赞」→ 计数 +1。{@link #apply} 因此在「槽集合原本不存在」这一<b>冷路径</b>上
     * 查一次 {@code media_like} 灾备表：确实赞过则只恢复成员、不重复计数。
     * 把成本放在冷路径而非热路径，是这个兜底能落地的关键。</p>
     */
    private static final Duration SET_TTL = Duration.ofDays(8);
    /**
     * 计数 Key TTL（A4：与去重集合<b>解耦</b>，取更长）。
     *
     * <p>计数 Key 只存一个整数、成本极低，没有理由跟去重集合同寿。取 30d 并每次读写续期，
     * 使「点赞数」在去重集合因长期无人访问而回收后依然可读；真要精确重建时用
     * {@link #rebuildFromDb(String)} 从灾备表恢复。</p>
     */
    private static final Duration COUNT_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final BehaviorEventPublisher behaviorPublisher;
    private final MediaLikeJdbcRepository likeRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Executor interactionExecutor;
    private final UploadRateLimiter rateLimiter;
    /** 计数增量发布器（opt-in）：{@code turbofeed.counter.service.enabled=false} 时工厂不提供 Bean，恒为空。 */
    private final ObjectProvider<CounterEventPublisher> counterPublisher;
    /** 通知生成器（ADR-0009，fail-open 单点生成）：点赞通知内容作者。 */
    private final NotificationGenerator notificationGenerator;

    public MediaLikeService(StringRedisTemplate redisTemplate, BehaviorEventPublisher behaviorPublisher,
                            MediaLikeJdbcRepository likeRepository, SnowflakeIdGenerator snowflakeIdGenerator,
                            @Qualifier("interactionExecutor") Executor interactionExecutor,
                            UploadRateLimiter rateLimiter,
                            ObjectProvider<CounterEventPublisher> counterPublisher,
                            NotificationGenerator notificationGenerator) {
        this.redisTemplate = redisTemplate;
        this.behaviorPublisher = behaviorPublisher;
        this.likeRepository = likeRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.interactionExecutor = interactionExecutor;
        this.rateLimiter = rateLimiter;
        this.counterPublisher = counterPublisher;
        this.notificationGenerator = notificationGenerator;
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
     * 去重集合已不可信时，用灾备表确认「此前是否赞过」（fail-open）。
     *
     * <p><b>为什么这里还要再包一层 try</b>：{@link MediaLikeJdbcRepository#existsLike} 内部已
     * fail-open，但那只是「当前实现」的保证。若这层兜底查询抛到 {@link #apply} 的 catch 里，
     * 结果是「成员已 SADD（红心亮了）但计数没加、增量没发、灾备没落库」——比不做兜底还糟。
     * 兜底必须<b>只能改善、不能恶化</b>，因此在调用点再兜一次：查不动就按新赞放行。</p>
     */
    private boolean alreadyLikedInDb(String postId, long userId) {
        try {
            return likeRepository.existsLike(postId, userId);
        } catch (Exception e) {
            log.warn("去重兜底查询失败（按新赞放行）: postId={}, userId={}, {}", postId, userId, e.getMessage());
            return false;
        }
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
                // 去重集合回收兜底：先记下这个槽「原本在不在」。集合不存在 = 该帖的去重状态
                // 已经不可信（新帖从未被赞，或老帖的 64 槽被 TTL 回收了），此时才需要查灾备表
                // 确认真实点赞史——热路径（集合存在）不付这次 DB 查询的代价。
                Boolean slotExisted = redisTemplate.hasKey(key);
                Long added = redisTemplate.opsForSet().add(key, uid);
                if (added != null && added == 1L) {
                    redisTemplate.expire(key, SET_TTL);
                    if (!Boolean.TRUE.equals(slotExisted) && alreadyLikedInDb(postId, userId)) {
                        // 去重集合已被回收，但灾备表证明该用户此前赞过 → 判为「重复赞」：
                        // 成员已由上面的 SADD 恢复（红心态正确），但不重复计数、不重复发增量、不重复落库。
                        log.info("去重集合已回收，据灾备表判定为重复赞（不计入计数）: postId={}, userId={}",
                                postId, userId);
                        return true;
                    }
                    // A1：与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                    bumpCount(postId, 1);
                    publish(postId, userId, "LIKE");
                    // ① 通知内容作者（ADR-0009，fail-open 单点生成，绝不阻塞点赞主流程）
                    notifyLike(postId, userId);
                    // ② 持久聚合底座（P1-2，opt-in）：实际新增赞才发 +1 增量
                    notifyCounter(postId, 1);
                    // ③ 异步落库（灾备），主链路不阻塞 DB 写
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
                    // A1：与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                    bumpCount(postId, -1);
                    publish(postId, userId, "UNLIKE");
                    // ② 持久聚合底座（P1-2，opt-in）：实际取消赞才发 -1 增量
                    notifyCounter(postId, -1);
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

    /** 用户是否已赞该内容（仅查其归属槽，O(1)）。 */
    public boolean isLiked(String postId, long userId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(slotKey(postId, slotOf(userId)), String.valueOf(userId)));
        } catch (Exception e) {
            log.warn("已赞态读取失败（按未赞处理）: postId={}, userId={}, {}", postId, userId, e.getMessage());
            return false;
        }
    }

    /**
     * 批量已赞态（A1：一次 pipeline {@code SISMEMBER} 取 N 条）。
     *
     * <p>供列表页 {@code enrichCounts} 调用，取代循环内逐条 {@link #isLiked}。同一用户的槽号
     * 由 {@code slotOf(userId)} 决定、与 postId 无关，因此 N 条只需 N 个 {@code SISMEMBER}。</p>
     *
     * @return postId → 是否已赞；<b>只包含已赞的项</b>（未赞与读取失败均不出现，调用方按未赞兜底）
     */
    public Map<String, Boolean> isLikedBatch(Collection<String> postIds, long userId) {
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
            log.warn("批量已赞态读取失败（按未赞处理）: size={}, userId={}, {}",
                    ids.size(), userId, e.getMessage());
        }
        return result;
    }

    /**
     * 当前内容点赞数（A1：单 Key {@code GET}，O(1)）。
     *
     * <p>计数 Key 缺失（迁移期历史数据 / TTL 过期 / Redis 重建）时回退 64 槽 {@code SCARD}
     * 求和并写回预热——去重集合是幂等权威，任何时刻都算得准，因此迁移期与故障期都安全。</p>
     *
     * <p><b>A4</b>：本方法是内容详情页的读路径，顺带对 64 槽去重集合做滑动续期（一次 pipeline），
     * 保证「仍在被浏览的内容」不会因固定 TTL 到期而丢失幂等语义。列表批量路径
     * {@link #likeCounts(Collection)} 刻意不续期，见 {@link #SET_TTL} 注释。</p>
     */
    public long likeCount(String postId) {
        try {
            String v = redisTemplate.opsForValue().get(countKey(postId));
            touchSlotTtls(postId);
            if (v != null) {
                return Long.parseLong(v);
            }
            long slots = sumSlotCardinalities(postId);
            trySetCount(postId, slots);
            return slots;
        } catch (Exception e) {
            log.warn("点赞数读取失败（回退 64 槽聚合）: postId={}, {}", postId, e.getMessage());
            try {
                return sumSlotCardinalities(postId);
            } catch (Exception e2) {
                log.warn("点赞数回退聚合亦失败（按 0 处理）: postId={}, {}", postId, e2.getMessage());
                return 0L;
            }
        }
    }

    /**
     * 批量点赞数（A1：一次 {@code MGET} 取 N 个计数 Key）。
     *
     * <p>供列表页 {@code enrichCounts} 调用：改造前是「N 条 × 64 槽 SCARD」= 一页 20 条
     * 1280 个 Redis 命令、20 次独立往返；现在热路径是 1 次 {@code MGET}（N 个命令、1 次往返）。
     * 冷启动项（计数 Key 缺失）合并进<b>一个</b> pipeline 批量回退，不会退化成 N 次往返。</p>
     *
     * @param postIds 内容身份（时间线幂等键 {@code timelineKey}）集合
     * @return postId → 点赞数；读取失败的项不出现在结果里（调用方按 0 / DB 冗余值兜底）
     */
    public Map<String, Long> likeCounts(Collection<String> postIds) {
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
                // 冷启动回退。刻意拆成两个 pipeline，而不是把 SCARD 与 EXPIRE 交错发在同一个里：
                // 交错时返回值列表是 [SCARD, EXPIRE, SCARD, EXPIRE, ...]，回读要靠手动跳格对齐索引，
                // 极易错位；拆开后「需要读结果的」与「不需要读结果的」各自连续，索引天然对齐。
                // pipeline ①：全部 SCARD（missing × 64 槽），返回值按 (项序 × 64 + 槽序) 连续排列
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
                // pipeline ②：续期 + 计数预热写回，一次往返（都不需要读返回值）
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (int idx = 0; idx < warmed.size(); idx++) {
                        String id = warmed.get(idx);
                        // A4 续期：冷启动本就要逐槽 SCARD，顺带续期是零额外成本（EXPIRE 对不存在 Key 是 no-op）
                        for (int slot = 0; slot < SLOT_COUNT; slot++) {
                            connection.keyCommands().expire(
                                    slotKey(id, slot).getBytes(StandardCharsets.UTF_8), SET_TTL.getSeconds());
                        }
                        // 预热写回：SET NX EX（单命令原子），避免无条件 SET 覆盖并发 INCR 的增量
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
            log.warn("批量点赞数读取失败（调用方按 DB 冗余值兜底）: size={}, {}", ids.size(), e.getMessage());
        }
        return result;
    }

    /**
     * 显式对账（A1/A4）：用 64 槽去重集合（幂等权威）重算并<b>覆盖</b>计数 Key。
     *
     * <p>计数 Key 靠 {@code INCR}/{@code DECR} 维护，与去重集合的变更之间存在极小的非原子窗口
     * （集合成功、计数失败）；长期运行可能累积漂移。本方法供定时巡检 / 管理端触发校正，
     * 使「Redis 是权威计数源」从注释承诺变成可对账、可修复的能力。</p>
     *
     * @return 校正后的点赞数（= 64 槽基数之和）
     */
    public long recountFromSlots(String postId) {
        long slots = sumSlotCardinalities(postId);
        try {
            redisTemplate.opsForValue().set(countKey(postId), String.valueOf(slots), COUNT_TTL);
        } catch (Exception e) {
            log.warn("点赞计数对账写回失败: postId={}, {}", postId, e.getMessage());
        }
        return slots;
    }

    /** 计数 Key（单 Key，不打散——见类注释「为什么计数 Key 不打散」）。 */
    private static String countKey(String postId) {
        return COUNT_KEY_PREFIX + postId;
    }

    /**
     * 计数 Key 增量维护（A1）。fail-open：读路径有 64 槽回退 + {@link #recountFromSlots} 对账兜底。
     *
     * <p><b>冷启动校正（必须）</b>：{@code INCR}/{@code DECR} 对<b>不存在</b>的 Key 会创建为 0 再运算，
     * 于是计数从 0 起算而非「历史总数 + delta」。典型场景：本改造上线时既有帖子从未写过计数 Key
     * （迁移期）、计数 Key TTL 过期而去重集合仍在、Redis 重建。不校正的话，一个已有 500 赞的帖子
     * 被点赞后计数会<b>变成 1</b>；取消赞更会算出 {@code -1}。</p>
     *
     * <p><b>为什么用 Lua 而不是「返回值 == delta 即冷启动」</b>：后者会误报——新帖计数为 0 时被点赞，
     * {@code INCR} 同样返回 1，于是每次都会白跑一次 64 槽聚合。Lua 里先 {@code EXISTS} 判定，
     * 精确区分「Key 原本不存在」与「计数恰好等于 delta」。<b>单 Key 脚本在 Redis Cluster 下是安全的</b>
     * （不触发 {@code CROSSSLOT}），且把 {@code INCRBY} + {@code PEXPIRE} 合并为一次往返
     * ——比改造前少一次 RTT，热路径成本不增反降。</p>
     *
     * <p>校正口径安全：调用方在进入本方法前已完成 {@code SADD}/{@code SREM}，
     * 因此 64 槽基数之和<b>就是</b>校正后的正确总数。写回用 {@code SETNX} 语义
     * （{@link #trySetCount}）：并发下若别的线程已初始化，则改为在其之上补一次 delta，不覆盖不丢增量。</p>
     */
    private void bumpCount(String postId, int delta) {
        String key = countKey(postId);
        try {
            Long v = redisTemplate.execute(LikeCountScript.COUNT_BUMP, List.of(key),
                    String.valueOf(delta), String.valueOf(COUNT_TTL.toMillis()));
            if (v != null) {
                return; // 热路径：Key 原本存在，已完成 INCRBY + PEXPIRE
            }
            // 冷启动：Key 原本不存在 → 用 64 槽去重集合（幂等权威）重算
            long slots = sumSlotCardinalities(postId);
            if (trySetCount(postId, slots)) {
                log.info("点赞计数冷启动校正（计数 Key 缺失，按 64 槽去重集合重算）: postId={}, corrected={}",
                        postId, slots);
            } else {
                // 并发下已被别的线程初始化 → 在其之上补本次 delta，避免覆盖丢增量
                redisTemplate.opsForValue().increment(key, delta);
            }
        } catch (Exception e) {
            log.warn("点赞计数 Key 维护失败（fail-open，读路径回退 64 槽）: postId={}, delta={}, {}",
                    postId, delta, e.getMessage());
        }
    }

    /**
     * 冷启动预热：仅当计数 Key <b>仍不存在</b>时写入（{@code SETNX} 语义）。
     *
     * <p>刻意不用 {@code SET}：预热读与并发点赞的 {@code INCR} 若交错，无条件 {@code SET}
     * 会把窗口内的增量覆盖掉。{@code SETNX} 保证「谁先写谁赢，后来的增量不丢」。</p>
     */
    private boolean trySetCount(String postId, long value) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(countKey(postId), String.valueOf(value), COUNT_TTL));
        } catch (Exception ignore) {
            // 预热失败不影响本次返回；下次读仍走回退
            return false;
        }
    }

    /**
     * A4 滑动续期：一次 pipeline 刷新 64 槽去重集合的 TTL。
     *
     * <p>只在<b>单条</b>读路径调用（内容详情页）。fail-open：续期失败最坏退化为「按原固定 TTL 到期」，
     * 与改造前行为一致，不影响计数返回。</p>
     */
    private void touchSlotTtls(String postId) {
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    // EXPIRE 对不存在的 Key 是 no-op（返回 0），不会误建空 Key
                    connection.keyCommands().expire(
                            slotKey(postId, slot).getBytes(StandardCharsets.UTF_8), SET_TTL.getSeconds());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("点赞去重集合续期失败（fail-open，退化为固定 TTL）: postId={}, {}", postId, e.getMessage());
        }
    }

    /**
     * A4 灾备重建：从 {@code media_like} 表恢复去重集合与计数 Key。
     *
     * <p>{@code media_like} 表本就是「为灾备重建而落库」（见类注释「持久化兜底」），但改造前全仓
     * 没有任何重建入口——Redis 数据丢失后无法恢复，注释承诺无法执行。本方法把该承诺变成可执行动作，
     * 供管理端 / 巡检在 Redis 重建、计数漂移、TTL 误回收等场景触发。</p>
     *
     * <p><b>幂等</b>：{@code SADD} 天然去重，重复执行结果一致；计数 Key 用 {@code SET} 覆盖
     * （此处是权威重建，不是预热，故不用 {@code SETNX}）。</p>
     *
     * @return 重建后的点赞数（= DB 中该内容的点赞行数）
     */
    public long rebuildFromDb(String postId) {
        List<Long> userIds = likeRepository.listUserIds(postId);
        if (userIds.isEmpty()) {
            // DB 无记录：清空 Redis 侧残留，避免"已取消的赞"因集合残留而复活
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
            log.warn("点赞去重集合重建失败: postId={}, rows={}, {}", postId, userIds.size(), e.getMessage());
        }
        long count = userIds.size();
        try {
            redisTemplate.opsForValue().set(countKey(postId), String.valueOf(count), COUNT_TTL);
        } catch (Exception e) {
            log.warn("点赞计数 Key 重建写回失败: postId={}, {}", postId, e.getMessage());
        }
        log.info("点赞数据已从 DB 灾备重建: postId={}, 恢复点赞数={}", postId, count);
        return count;
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

    /** 发计数增量给 tf-counter 聚合底座（P1-2，opt-in）。fail-open：失败仅告警，不影响点赞主流程。 */
    private void notifyCounter(String postId, int delta) {
        try {
            counterPublisher.ifAvailable(c -> c.incr(postId, "LIKE", delta));
        } catch (Exception e) {
            log.warn("计数增量通知 tf-counter 失败（fail-open，不影响主流程）: postId={}, delta={}, {}",
                    postId, delta, e.getMessage());
        }
    }

    /**
     * 点赞通知内容作者（ADR-0009，fail-open 单点生成）。
     *
     * <p>作者由 postId 零 DB 解析（{@link MediaKeyUtil#parseUserId}）；自赞 / 解析失败跳过。
     * {@link NotificationGenerator#generate} 内部已异步 + 兜底，本方法再包一层 try 仅作双保险。</p>
     */
    private void notifyLike(String postId, long actorId) {
        try {
            long authorId = MediaKeyUtil.parseUserId(postId);
            if (authorId <= 0 || authorId == actorId) {
                return; // 解析失败或自赞，不通知
            }
            String eventId = "LIKE:" + postId + ":" + actorId;
            notificationGenerator.generate(new NotificationEvent(eventId, NotificationType.LIKE,
                    authorId, actorId, "POST", postId, "赞了你的作品", null, Instant.now()));
        } catch (Exception e) {
            log.warn("点赞通知生成失败（fail-open，不影响主流程）: postId={}, actorId={}, {}",
                    postId, actorId, e.getMessage());
        }
    }
}
