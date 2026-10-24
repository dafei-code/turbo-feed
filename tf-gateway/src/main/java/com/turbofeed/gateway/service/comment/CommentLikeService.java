package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.util.LikeCountScript;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li><b>计数聚合（A2：独立计数 Key，批量走 MGET）</b>：{@code likeCount} 走单 Key
 *       {@code tf:comment:like:count:{commentId}}（{@code INCR}/{@code DECR} 与去重集合同步变更）；
 *       批量 {@link #likeCounts(Collection)} 用<b>一次 {@code MGET}</b> 取 N 条。
 *       改造前批量路径是「N 条 × 64 槽 SCARD」——20 条评论 = <b>1280 个 Redis 命令</b>，
 *       pipeline 只省了往返、省不掉 Redis 单线程串行执行 1280 个命令的开销。
 *       <b>计数 Key 不打散</b>的理由同 media 点赞：64 槽是为解决 Set <b>元素膨胀</b>的写热键，
 *       计数 Key 只存整数、{@code INCR} 恒 O(1)，打散它纯粹是读放大。</li>
 *   <li><b>平滑迁移与对账</b>：计数 Key 缺失（历史数据 / 过期 / Redis 重建）时回退 64 槽
 *       {@code SCARD} 求和并写回预热；{@link #recountFromSlots(String)} 提供显式对账、
 *       {@link #rebuildFromDb(String, long)} 提供从灾备表 {@code comment_like} 重建的能力。</li>
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
    /**
     * 点赞计数 Key 前缀：{@code tf:comment:like:count:{commentId} => 整数}（A2）。
     * 单 Key 不打散——只存整数，{@code INCR} 恒 O(1) 且无元素膨胀，不存在 Set 那样的写热键问题。
     */
    static final String COUNT_KEY_PREFIX = "tf:comment:like:count:";
    /** 热键打散槽数：与 counter / media 点赞 bucket-count=64 对齐，读扩散到 64 个 Key。 */
    private static final int SLOT_COUNT = 64;
    /**
     * 去重集合 TTL：略长于内容公域生命周期，内容下架后自然过期。
     *
     * <p><b>A4 续期点</b>：{@code toggle} 的 {@code SADD} 成功分支（被触碰的槽）、
     * {@link #likeCount(String)} 单条读（评论详情页，一次 pipeline 刷新 64 槽）、
     * {@link #likeCounts(Collection)} 的<b>冷启动回退项</b>（本就要逐槽 {@code SCARD}，顺带续期零额外成本）。</p>
     *
     * <p><b>刻意不做的续期</b>：批量路径的<b>热命中项</b>（计数 Key 存在）不续期——那是 QPS 最高处，
     * 为续期再加 N×64 个 {@code EXPIRE} 会把 A2 省下的开销全还回去。</p>
     *
     * <p><b>残余漂移窗口（已知取舍，非缺陷隐藏）</b>：某评论连续 8 天只被批量热命中读取、
     * 且无任何新点赞时，其去重集合会到期；此后曾点赞的用户再点一次，{@code SADD} 返回 1 被当作
     * 「新赞」→ 计数 Key 多 +1。该漂移<b>有界</b>（每用户每评论至多 1）且可修复：
     * {@link #recountFromSlots(String)} 用集合重算、{@link #rebuildFromDb(String, long)} 用灾备表
     * {@code comment_like} 权威重建。彻底消除需在热路径续期，代价与收益不成比例，故不采用。</p>
     */
    private static final Duration SET_TTL = Duration.ofDays(8);
    /**
     * 计数 Key TTL（A4：与去重集合<b>解耦</b>，取更长）。
     *
     * <p>计数 Key 只存一个整数、成本极低，没有理由跟去重集合同寿。取 30d 且每次读写续期，
     * 使「点赞数」在去重集合回收后依然可读；需要精确值时用 {@link #rebuildFromDb(String, long)} 恢复。</p>
     */
    private static final Duration COUNT_TTL = Duration.ofDays(30);

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
                // A2：与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                bumpCount(String.valueOf(commentId), 1);
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
                // A2：与去重集合同步维护计数 Key（读权威）；fail-open，失败仅告警
                bumpCount(String.valueOf(commentId), -1);
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

    /**
     * 单条评论点赞数（A2：单 Key {@code GET}，O(1)）。
     *
     * <p>计数 Key 缺失时回退 64 槽 {@code SCARD} 求和并写回预热；本方法为评论详情页读路径，
     * 顺带对 64 槽去重集合滑动续期（见 {@link #SET_TTL} 的 A4 续期点说明）。</p>
     */
    public long likeCount(String commentId) {
        try {
            String v = redisTemplate.opsForValue().get(countKey(commentId));
            touchSlotTtls(commentId);
            if (v != null) {
                return Long.parseLong(v);
            }
            long slots = sumSlotCardinalities(commentId);
            trySetCount(commentId, slots);
            return slots;
        } catch (Exception e) {
            log.warn("评论点赞数读取失败（回退 64 槽聚合）: commentId={}, {}", commentId, e.getMessage());
            try {
                return sumSlotCardinalities(commentId);
            } catch (Exception e2) {
                log.warn("评论点赞数回退聚合亦失败（按 0 处理）: commentId={}, {}", commentId, e2.getMessage());
                return 0L;
            }
        }
    }

    /**
     * 批量点赞数（A2：一次 {@code MGET} 取 N 个计数 Key）。
     *
     * <p>改造前是「N 条 × 64 槽 SCARD」——20 条评论 = 1280 个 Redis 命令，pipeline 只省往返、
     * 省不掉 Redis 单线程串行执行 1280 个命令的开销。现在热路径是 1 次 {@code MGET}。</p>
     *
     * <p>冷启动项（计数 Key 缺失）合并进<b>一个</b> pipeline 批量回退（缺失项 × 64 槽），
     * 并顺带续期——那里本就要逐槽 {@code SCARD}，续期是零额外成本。</p>
     *
     * @return commentId → 点赞数；整体读取失败时返回空 Map（调用方按 DB 冗余值兜底）
     */
    public Map<Long, Long> likeCounts(Collection<Long> commentIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (commentIds == null || commentIds.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<>(commentIds);
        try {
            List<String> countKeys = new ArrayList<>(ids.size());
            for (Long cid : ids) {
                countKeys.add(countKey(String.valueOf(cid)));
            }
            List<String> vals = redisTemplate.opsForValue().multiGet(countKeys);
            List<Long> missing = new ArrayList<>();
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
                    for (Long cid : missing) {
                        for (int slot = 0; slot < SLOT_COUNT; slot++) {
                            connection.setCommands().sCard(
                                    slotKey(String.valueOf(cid), slot).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    return null;
                });
                // pipeline ②：续期 + 计数预热写回，一次往返（都不需要读返回值）
                List<Long> warmed = new ArrayList<>(missing.size());
                List<Long> warmValues = new ArrayList<>(missing.size());
                for (int idx = 0; idx < missing.size(); idx++) {
                    long sum = 0L;
                    for (int slot = 0; slot < SLOT_COUNT; slot++) {
                        sum += sizeOf(sizes.get(idx * SLOT_COUNT + slot));
                    }
                    Long cid = missing.get(idx);
                    result.put(cid, sum);
                    warmed.add(cid);
                    warmValues.add(sum);
                }
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (int idx = 0; idx < warmed.size(); idx++) {
                        String cidStr = String.valueOf(warmed.get(idx));
                        // A4 续期：冷启动本就要逐槽 SCARD，顺带续期是零额外成本（EXPIRE 对不存在 Key 是 no-op）
                        for (int slot = 0; slot < SLOT_COUNT; slot++) {
                            connection.keyCommands().expire(
                                    slotKey(cidStr, slot).getBytes(StandardCharsets.UTF_8), SET_TTL.getSeconds());
                        }
                        // 预热写回：SET NX EX（单命令原子），避免无条件 SET 覆盖并发 INCR 的增量
                        connection.stringCommands().set(
                                countKey(cidStr).getBytes(StandardCharsets.UTF_8),
                                String.valueOf(warmValues.get(idx)).getBytes(StandardCharsets.UTF_8),
                                Expiration.from(COUNT_TTL),
                                RedisStringCommands.SetOption.SET_IF_ABSENT);
                    }
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("评论批量点赞数读取失败（按 DB 冗余值兜底）: size={}, {}", ids.size(), e.getMessage());
        }
        return result;
    }

    /**
     * 显式对账（A2/A4）：用 64 槽去重集合（幂等权威）重算并<b>覆盖</b>计数 Key。
     *
     * @return 校正后的点赞数（= 64 槽基数之和）
     */
    public long recountFromSlots(String commentId) {
        long slots = sumSlotCardinalities(commentId);
        try {
            redisTemplate.opsForValue().set(countKey(commentId), String.valueOf(slots), COUNT_TTL);
        } catch (Exception e) {
            log.warn("评论点赞计数对账写回失败: commentId={}, {}", commentId, e.getMessage());
        }
        return slots;
    }

    /**
     * A4 灾备重建：从 {@code comment_like} 表恢复去重集合与计数 Key。
     *
     * <p>{@code comment_like} 表本就是「Redis 为权威、DB 为灾备」中的灾备侧，但改造前只写不读，
     * 灾备承诺无法执行。本方法供管理端 / 巡检在 Redis 重建、计数漂移、TTL 误回收时触发。
     * {@code SADD} 天然幂等，重复执行结果一致。</p>
     *
     * @param mediaId   分片键（{@code comment_like} 按 media_id 分片，必须带上避免广播）
     * @param commentId 被重建的评论 ID
     * @return 重建后的点赞数（= DB 中该评论的点赞行数）
     */
    public long rebuildFromDb(String mediaId, long commentId) {
        List<Long> userIds = likeRepository.listUserIds(mediaId, commentId);
        String cid = String.valueOf(commentId);
        if (userIds.isEmpty()) {
            // DB 无记录：清掉计数 Key，避免"已取消的赞"因残留而虚高
            try {
                redisTemplate.delete(countKey(cid));
            } catch (Exception ignore) {
                // 清理失败不影响返回
            }
            return 0L;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long uid : userIds) {
                    byte[] k = slotKey(cid, slotOf(uid)).getBytes(StandardCharsets.UTF_8);
                    connection.setCommands().sAdd(k, String.valueOf(uid).getBytes(StandardCharsets.UTF_8));
                    connection.keyCommands().expire(k, SET_TTL.getSeconds());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("评论点赞去重集合重建失败: commentId={}, rows={}, {}", commentId, userIds.size(), e.getMessage());
        }
        long count = userIds.size();
        try {
            redisTemplate.opsForValue().set(countKey(cid), String.valueOf(count), COUNT_TTL);
        } catch (Exception e) {
            log.warn("评论点赞计数 Key 重建写回失败: commentId={}, {}", commentId, e.getMessage());
        }
        log.info("评论点赞数据已从 DB 灾备重建: mediaId={}, commentId={}, 恢复点赞数={}", mediaId, commentId, count);
        return count;
    }

    /** 计数 Key（单 Key，不打散——见类注释「计数 Key 不打散」）。 */
    private static String countKey(String commentId) {
        return COUNT_KEY_PREFIX + commentId;
    }

    /**
     * 计数 Key 增量维护（A2）。fail-open：读路径有 64 槽回退 + {@link #recountFromSlots} 对账兜底。
     *
     * <p><b>冷启动校正（必须）</b>：与内容点赞侧同一缺陷——{@code INCR}/{@code DECR} 对<b>不存在</b>的
     * Key 会创建为 0 再运算，于是计数从 0 起算而非「历史总数 + delta」。触发场景：本改造上线时
     * 既有评论从未写过计数 Key（迁移期）、计数 Key TTL 过期而去重集合仍在、Redis 重建。
     * 不校正的话，一条已有 500 赞的评论被点赞后计数会<b>变成 1</b>；取消赞更会算出 {@code -1}。</p>
     *
     * <p>与 {@code MediaLikeService#bumpCount} 共用 {@link LikeCountScript#COUNT_BUMP}：
     * 脚本内 {@code EXISTS} + {@code INCRBY} 原子执行，精确区分「Key 原本不存在」与
     * 「计数恰好等于 delta」，避免用免脚本写法（返回值 == delta）误报而白跑一次 64 槽聚合。</p>
     */
    private void bumpCount(String commentId, int delta) {
        String key = countKey(commentId);
        try {
            Long v = redisTemplate.execute(LikeCountScript.COUNT_BUMP, List.of(key),
                    String.valueOf(delta), String.valueOf(COUNT_TTL.toMillis()));
            if (v != null) {
                return; // 热路径：Key 原本存在，已完成 INCRBY + PEXPIRE
            }
            // 冷启动：Key 原本不存在 → 用 64 槽去重集合（幂等权威）重算
            long slots = sumSlotCardinalities(commentId);
            if (trySetCount(commentId, slots)) {
                log.info("评论点赞计数冷启动校正（计数 Key 缺失，按 64 槽去重集合重算）: commentId={}, corrected={}",
                        commentId, slots);
            } else {
                // 并发下已被别的线程初始化 → 在其之上补本次 delta，避免覆盖丢增量
                redisTemplate.opsForValue().increment(key, delta);
            }
        } catch (Exception e) {
            log.warn("评论点赞计数 Key 维护失败（fail-open，读路径回退 64 槽）: commentId={}, delta={}, {}",
                    commentId, delta, e.getMessage());
        }
    }

    /**
     * 冷启动预热：仅当计数 Key <b>仍不存在</b>时写入（{@code SETNX} 语义）。
     * 刻意不用 {@code SET}：预热读与并发点赞的 {@code INCR} 交错时，无条件覆盖会丢窗口内增量。
     *
     * @return {@code true} = 本次写入成功（冷启动校正已生效）；{@code false} = Key 已被并发线程初始化
     */
    private boolean trySetCount(String commentId, long value) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(countKey(commentId), String.valueOf(value), COUNT_TTL));
        } catch (Exception ignore) {
            // 预热失败不影响本次返回；下次读仍走回退
            return false;
        }
    }

    /**
     * A4 滑动续期：一次 pipeline 刷新 64 槽去重集合的 TTL。
     * 只在<b>单条</b>读路径（评论详情页）调用；批量热命中路径刻意不续期，见 {@link #SET_TTL} 注释。
     */
    private void touchSlotTtls(String commentId) {
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    // EXPIRE 对不存在的 Key 是 no-op（返回 0），不会误建空 Key
                    connection.keyCommands().expire(
                            slotKey(commentId, slot).getBytes(StandardCharsets.UTF_8), SET_TTL.getSeconds());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("评论点赞去重集合续期失败（fail-open，退化为固定 TTL）: commentId={}, {}",
                    commentId, e.getMessage());
        }
    }

    /** 64 槽去重集合基数求和（幂等权威口径）：用于冷启动重建与对账。 */
    private long sumSlotCardinalities(String commentId) {
        List<Object> sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                connection.setCommands().sCard(slotKey(commentId, slot).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        return sumSizes(sizes);
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
