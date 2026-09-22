package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公域发现流时间线读模型（写时物化）。
 *
 * <p><b>从 tf-gateway 迁入</b>：本类原属网关 {@code com.turbofeed.gateway.service.feed}，
 * 是"Feed 引擎推模式"在网关进程内的雏形。服务拆分（见 docs/architecture/service-split.md）
 * 后，Feed 时间线的物化与读取整体归位到本引擎——网关只保留 HTTP 接入与审核状态机，
 * 通过内部接口投递变更，不再直接持有任何 Feed 读模型。</p>
 *
 * <p><b>解决的问题</b>：原 {@code MediaJdbcRepository#listApprovedGlobal} 不带分片键，
 * ShardingSphere 广播到全部分片归并——百亿行下每翻一页都全表扫，直接拖垮所有分片。
 * 本类把可见性从"读时扫描"变为"写时产生"：审核通过即写入 Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}，读路径合并近 {@link #MERGE_BUCKETS} 天分桶按 score
 * 归并，每请求 O(log n) 级、完全不碰 media 分片库。</p>
 *
 * <p><b>分桶维度 = 入流（过审）时刻，不是上传时刻</b>：早期实现用
 * {@link FeedItemView#createdAt()}（上传时间）决定桶与 score，于是「上传后隔天才过审」的内容
 * 落进旧桶，而读路径只并最近 {@code MERGE_BUCKETS} 天 → 该内容<b>永远不会出现在发现流</b>。
 * 现统一以 {@code Instant.now()}（过审入流时刻）分桶与打分，保证「今天过审 → 今天可见」；
 * {@code createdAt} 仅作为展示用的发布时间，不参与分桶与排序。</p>
 *
 * <p><b>一个成员 = 一个帖子（一帖多图）</b>：物化单位是<b>帖子</b>而非单张图——同一次上传批次的
 * N 张图（{@link FeedItemView#images()}）共用一条成员串，前端据此渲染 9 图轮播。因此本类所有
 * 「定位一条内容」的地方统一使用 {@link FeedItemView#timelineKey()}（有 {@code postId} 用 postId，
 * 历史单图成员串回退 {@code mediaId}）作为幂等键与反查索引键；调用方不要各自拼装这个键，
 * 否则新旧数据会落在两个不同索引上，出现「下架无效、内容仍可见」。</p>
 *
 * <p><b>可删除性（合规要求）</b>：ZSET member 是 JSON 串，无法凭帖身份直接 {@code ZREM}。
 * 早期实现靠扫描每桶<b>最新 {@code PER_BUCKET_CAP} 条</b>定位目标，桶内超出该量的内容
 * <b>删不掉也下不了架</b>——这对内容安全是硬伤。现为每个帖子维护反查索引
 * {@code tf:feed:idx:{timelineKey} => {桶key}<SOH>{member}}，删除/下架走精确 {@code ZREM}，
 * 与桶内条数无关；索引缺失（历史数据/已过期）时才退回尽力扫描。</p>
 *
 * <p><b>容量</b>：桶与反查索引统一按 {@link #BUCKET_TTL} 过期，避免逐日累积的 ZSET
 * 无限膨胀（发现流只看最新，过期桶整体淘汰即可）。</p>
 *
 * <p><b>fail-open（默认路径）</b>：{@link #append}/{@link #remove} 写入/读取异常均只告警不抛，
 * 绝不影响调用方主流程，HTTP 兜底投递路径仍用它。需强一致（异常上抛以触发 MQ 重试/DLQ）时，
 * 调用方改用 {@link #appendStrict}/{@link #removeStrict}（B2 顺序消息消费者即用此路径）。
 * 注意本类位于引擎侧后，"读不到"不再回源分片库（引擎无 DB 依赖），降级口径由网关按
 * {@code turbofeed.feed.degraded-mode} 决定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TL_PREFIX = "tf:feed:tl:";
    /** 反查索引前缀：帖身份（postId，历史数据回退 mediaId）-&gt; 所在桶 key + 成员串（用于精确 ZREM）。 */
    private static final String IDX_PREFIX = "tf:feed:idx:";
    /** 合并最近 N 天分桶（覆盖发现流"看最新"诉求，同时限制归并成本）。 */
    private static final int MERGE_BUCKETS = 3;
    /** 单桶最多拉取条数（防单天批准量过大时归并开销爆炸）。 */
    private static final int PER_BUCKET_CAP = 500;
    /** 流量池层级上限（L1 小池 / L3 大池，演示 3 级）。包可见：晋级器据此判断封顶。 */
    static final int MAX_POOL_LEVELS = 3;
    /** 桶与反查索引的存活时长：超过该天数的桶整体淘汰（发现流只看最新）。 */
    private static final Duration BUCKET_TTL = Duration.ofDays(7);
    /** 反查索引值里「桶 key」与「成员串」的分隔符（SOH 不可打印字符，不会出现在 key 与 JSON 中）。 */
    private static final String IDX_SEP = "\u0001";

    /** 推荐流是否按流量池权重分配每页槽位（抖音式曝光分层）。false 退化为"全池合并+全局倒序"。 */
    @Value("${turbofeed.feed.pool-read-weight-enabled:true}")
    private boolean poolReadWeightEnabled;
    /** 各流量池在推荐流中的曝光权重（按池序 L1..Ln，默认 10%/30%/60%：新内容试水/已验证优质占大头）。 */
    @Value("${turbofeed.feed.pool-weights:0.1,0.3,0.6}")
    private String poolWeightsCsv;

    /**
     * 审核通过：按信用池写时间线（denormalized {@link FeedItemView} JSON），fail-open。
     * 仅 {@code status=APPROVED} 入对应池。
     *
     * <p>分桶与 score 均取<b>当前入流时刻</b>（过审时刻），不取 {@code createdAt}。
     * 写入前先摘掉该内容可能存在的旧位置，避免「申诉翻案 / 重复过审」让同一内容在流里出现两次。</p>
     */
    public void append(FeedItemView item, int poolLevel) {
        try {
            appendStrict(item, poolLevel);
        } catch (Exception e) {
            log.warn("时间线写入失败（不影响主流程）: mediaId={}, pool={}, {}", item == null ? "null" : item.mediaId(), poolLevel, e.getMessage());
        }
    }

    /**
     * 严格写入入口（B2 顺序消息消费者使用）：不做 try/catch，异常向上抛，
     * 由 MQ 框架触发重试 / 进入 DLQ。{@code item} 为空或未过审时无声返回（不入时间线）。
     */
    public void appendStrict(FeedItemView item, int poolLevel) {
        if (item == null || !item.approved()) {
            return;
        }
        int pool = poolLevel < 1 ? 1 : Math.min(poolLevel, MAX_POOL_LEVELS);
        Instant approvedAt = Instant.now();
        String key = TL_PREFIX + pool + ":" + bucketOf(approvedAt);
        // 帖身份：有 postId 用 postId，历史单图数据回退 mediaId（见 FeedItemView#timelineKey）
        String timelineKey = item.timelineKey();
        String member;
        try {
            member = objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            // 严格路径：序列化失败必须上抛，交由 MQ 重试 / DLQ，绝不可静默吞掉。
            throw new IllegalStateException("Feed 时间线序列化失败（不入时间线）: key=" + timelineKey, e);
        }
        detach(timelineKey, null);
        redisTemplate.opsForZSet().add(key, member, approvedAt.toEpochMilli());
        redisTemplate.expire(key, BUCKET_TTL);
        redisTemplate.opsForValue().set(IDX_PREFIX + timelineKey, key + IDX_SEP + member, BUCKET_TTL);
    }

    /** 兼容重载：未指定池时落 L1 小池。 */
    public void append(FeedItemView item) {
        append(item, 1);
    }

    /**
     * 游标分页读取公域发现流（入流时间倒序）。合并近 {@code MERGE_BUCKETS} 天分桶，
     * 每桶取最新 {@code PER_BUCKET_CAP} 条归并后切片。
     *
     * <p>归并按 ZSET score（入流时刻）排序，而不是 {@code createdAt}（上传时间）——
     * 否则「早传晚审」的内容会被排到当日列表末尾，等于隐形。</p>
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    /**
     * 游标分页读取公域发现流（入流时间倒序）。
     *
     * <p><b>抖音式流量池曝光加权（默认开启）</b>：每个流量池代表不同的公域曝光量级
     * （L1 小池试水 / L3 大池全量）。开启后，每一页的槽位按 {@code pool-weights} 分配给各池、
     * 高池（已验证的优质内容）排前面占大头，低池（新内容）占小头——这正是抖音"赛马"的体感：
     * 新内容先拿到少量曝光，互动率达标才被晋级到更大池放大。关闭权重
     * （{@code pool-read-weight-enabled=false}）则退化为原"全池合并 + 按入流时刻全局倒序"，
     * 等价于改造前所有池一视同仁的语义（演示/回滚用）。</p>
     *
     * <p>分页在加权模式下按池各自推进游标：第 {@code page} 页时，池 p 贡献其候选列表的
     * {@code [page*alloc_p, page*alloc_p+alloc_p)} 切片，因此翻页稳定、不会重复或跳漏。</p>
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<FeedItemView> readPage(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        // 先按池收集近 N 天、每池最新的候选（保留 ZSET score 以便回退路径按入流时刻排序）
        Map<Integer, List<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
            byPool.put(pool, new ArrayList<>());
        }
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = TL_PREFIX + pool + ":" + date;
                try {
                    Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                            .reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY,
                                    Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                    if (tuples != null) {
                        byPool.get(pool).addAll(tuples);
                    }
                } catch (Exception e) {
                    log.warn("时间线读取失败: key={}, {}", key, e.getMessage());
                    return List.of();
                }
            }
        }

        if (!poolReadWeightEnabled) {
            // 回退：全池合并 + 按入流时刻（ZSET score）全局倒序，等价改造前语义
            List<ZSetOperations.TypedTuple<String>> merged = new ArrayList<>();
            for (List<ZSetOperations.TypedTuple<String>> l : byPool.values()) {
                merged.addAll(l);
            }
            merged.sort(Comparator.comparingDouble(
                    (ZSetOperations.TypedTuple<String> t) -> t.getScore() == null ? 0d : t.getScore()).reversed());
            long offset = (long) Math.max(page, 0) * limit;
            List<FeedItemView> items = new ArrayList<>(merged.size());
            for (ZSetOperations.TypedTuple<String> tuple : merged) {
                FeedItemView it = parseMember(tuple == null ? null : tuple.getValue());
                if (it != null) {
                    items.add(it);
                }
            }
            int from = (int) Math.min(offset, items.size());
            int to = (int) Math.min(offset + limit, items.size());
            return items.subList(from, to);
        }

        // 加权：每页按池权重分配槽位，高池优先排前
        double[] weights = parsePoolWeights();
        Map<Integer, List<FeedItemView>> parsed = new LinkedHashMap<>();
        for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
            List<FeedItemView> l = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> t : byPool.get(pool)) {
                FeedItemView it = parseMember(t == null ? null : t.getValue());
                if (it != null) {
                    l.add(it);
                }
            }
            parsed.put(pool, l);
        }
        int[] alloc = allocateSlots(limit, weights, parsed);
        long offset = (long) Math.max(page, 0) * limit;
        List<FeedItemView> result = new ArrayList<>(limit);
        for (int pool = MAX_POOL_LEVELS; pool >= 1; pool--) {
            List<FeedItemView> items = parsed.get(pool);
            int per = alloc[pool - 1];
            if (per <= 0 || items.isEmpty()) {
                continue;
            }
            int start = (int) Math.min((long) per * offset, items.size());
            int end = Math.min(start + per, items.size());
            if (start < end) {
                result.addAll(items.subList(start, end));
            }
        }
        return result;
    }

    private FeedItemView parseMember(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FeedItemView.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 解析并归一化池权重（CSV，按池序 L1..Ln）。非法/全零时退化为均匀分布。 */
    private double[] parsePoolWeights() {
        double[] w = new double[MAX_POOL_LEVELS];
        if (poolWeightsCsv != null) {
            String[] parts = poolWeightsCsv.split(",");
            for (int i = 0; i < MAX_POOL_LEVELS; i++) {
                if (i < parts.length) {
                    try {
                        w[i] = Double.parseDouble(parts[i].trim());
                    } catch (NumberFormatException ignore) {
                        w[i] = 0d;
                    }
                } else {
                    w[i] = 0d;
                }
            }
        }
        double sum = 0d;
        for (double x : w) {
            sum += x;
        }
        if (sum <= 0d) {
            for (int i = 0; i < MAX_POOL_LEVELS; i++) {
                w[i] = 1.0 / MAX_POOL_LEVELS;
            }
        } else {
            for (int i = 0; i < MAX_POOL_LEVELS; i++) {
                w[i] /= sum;
            }
        }
        return w;
    }

    /**
     * 把单页 {@code limit} 个槽位按权重分配到各池。floor 后把余量补给有内容的池（优先高池），
     * 并保证"有内容且页足够大"的池至少占 1 槽（新鲜内容永远有试水机会）。
     */
    private int[] allocateSlots(int limit, double[] weights, Map<Integer, List<FeedItemView>> byPool) {
        int[] alloc = new int[weights.length];
        int remaining = limit;
        for (int i = 0; i < weights.length; i++) {
            alloc[i] = (int) Math.floor(limit * weights[i]);
            remaining -= alloc[i];
        }
        if (limit >= weights.length) {
            for (int i = 0; i < weights.length && remaining >= 0; i++) {
                if (alloc[i] == 0 && !byPool.get(i + 1).isEmpty()) {
                    alloc[i] = 1;
                    remaining--;
                }
            }
        }
        int idx = weights.length - 1;
        while (remaining > 0) {
            if (!byPool.get(idx + 1).isEmpty()) {
                alloc[idx]++;
                remaining--;
            }
            idx = (idx - 1 + weights.length) % weights.length;
            if (idx == weights.length - 1 && remaining > 0) {
                boolean any = false;
                for (int i = 0; i < weights.length; i++) {
                    if (!byPool.get(i + 1).isEmpty()) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    break;
                }
            }
        }
        return alloc;
    }

    private String bucketOf(Instant t) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(t.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 从公域时间线移除某个<b>帖子</b>（用户删除、举报下架、申诉中暂不可见时调用）。
     *
     * <p>优先走反查索引做精确 {@code ZREM}——与桶内条数无关，因此对「桶内超过
     * {@code PER_BUCKET_CAP} 条」的内容同样有效（这正是早期扫描实现的失效场景）。
     * 索引缺失时退回遍历最近 {@code MERGE_BUCKETS} 天桶的尽力扫描（仅覆盖每桶最新
     * {@code PER_BUCKET_CAP} 条，历史数据可能漏删）。fail-open：任一异常只告警不抛。</p>
     *
     * @param timelineKey 帖身份（{@code FeedItemView#timelineKey()}：有 postId 用 postId，
     *                    历史单图数据回退 mediaId）。<b>必须与投递时一致</b>，否则反查索引对不上、下架失效
     */
    public void remove(String timelineKey) {
        try {
            removeStrict(timelineKey);
        } catch (Exception e) {
            log.warn("时间线移除失败（不影响主流程）: key={}, {}", timelineKey, e.getMessage());
        }
    }

    /**
     * 严格移除入口（B2 顺序消息消费者使用）：不做 try/catch，异常向上抛，
     * 由 MQ 框架触发重试 / 进入 DLQ。优先走反查索引精确 {@code ZREM}，
     * 索引缺失时退回尽力扫描。
     *
     * @param timelineKey 帖身份——必须与 {@code append} 时写入的键一致（{@code FeedItemView#timelineKey()}）
     */
    public void removeStrict(String timelineKey) {
        if (timelineKey == null) {
            return;
        }
        if (detach(timelineKey, IDX_PREFIX + timelineKey)) {
            return;
        }
        scanRemoveStrict(timelineKey);
    }

    /**
     * 流量池晋级（抖音式赛马）：把某帖从当前池搬到更高池，<b>仅升不降</b>。
     *
     * <p>复用现有 ZSET + 反查索引，不引新存储：先按反查索引定位原桶与成员串，
     * 用 {@code ZSCORE} 取回原始 score（入流时刻），再 {@code ZREM} 旧桶 + {@code ZADD} 新池桶
     * （同日期、同 score，仅池号 +1），最后把反查索引改写成新桶位置。下架走 {@link #remove}
     * 时据新索引精确摘除，不会漏删。</p>
     *
     * <p>晋级本身不改内容排序（score 不变），改变的是它所在的"曝光池"——
     * 读路径按池权重分配槽位（见 {@link #readPage}），进更高池 = 在推荐流里拿到更多占位。</p>
     *
     * @param timelineKey 帖身份（与 append 一致；有 postId 用 postId，历史数据回退 mediaId）
     * @param targetPool  目标池（必须 &gt; 当前池且 ≤ {@link #MAX_POOL_LEVELS}）
     * @return true = 已晋级；false = 不在池中 / 已是更高池 / 参数非法（无需动作）
     */
    public boolean promote(String timelineKey, int targetPool) {
        if (timelineKey == null || targetPool <= 0 || targetPool > MAX_POOL_LEVELS) {
            return false;
        }
        try {
            String idxKey = IDX_PREFIX + timelineKey;
            String location = redisTemplate.opsForValue().get(idxKey);
            if (location == null) {
                return false;
            }
            int sep = location.indexOf(IDX_SEP);
            if (sep <= 0) {
                return false;
            }
            String bucketKey = location.substring(0, sep);
            String member = location.substring(sep + IDX_SEP.length());
            // bucketKey 形如 tf:feed:tl:{oldPool}:{date}
            String rest = bucketKey.substring(TL_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            int oldPool = Integer.parseInt(rest.substring(0, colon));
            String date = rest.substring(colon + 1);
            if (oldPool >= targetPool) {
                return false;                       // 仅升不降，避免来回抖动
            }
            Double score = redisTemplate.opsForZSet().score(bucketKey, member);
            if (score == null) {
                return false;                       // 索引与 ZSET 不一致，放弃晋级
            }
            String newBucket = TL_PREFIX + targetPool + ":" + date;
            redisTemplate.opsForZSet().remove(bucketKey, member);
            redisTemplate.opsForZSet().add(newBucket, member, score);
            redisTemplate.expire(newBucket, BUCKET_TTL);
            // 反查索引改写到新桶位置：否则后续 remove 会 ZREM 旧桶（已空），下架失效
            redisTemplate.opsForValue().set(idxKey, newBucket + IDX_SEP + member, BUCKET_TTL);
            return true;
        } catch (Exception e) {
            log.warn("流量池晋级失败（不影响主流程）: timelineKey={}, targetPool={}, {}", timelineKey, targetPool, e.getMessage());
            return false;
        }
    }

    /**
     * 流量池降级（抖音式赛马的差评通道）：把某帖从当前池搬到更低池，<b>仅降不升</b>。
     *
     * <p>与 {@link #promote} 完全对称：复用同一套 ZSET + 反查索引，只改池号方向（{@code oldPool > targetPool}）。
     * score（入流时刻）不变，改变的是"曝光池"——读路径按池权重分配槽位，
     * 进更低池 = 在推荐流里拿到更少占位。下架走 {@link #remove} 时据新索引精确摘除，不会漏删。</p>
     *
     * <p><b>为什么是"步降一级"而非"一步打回 L1"</b>：晋级也是每轮最多升一级
     * （{@code decideTargetPool} 的语义），降级对称才不会出现"一次差评直接清零、之后要重新赛马爬升"的剧烈抖动；
     * 差评率持续偏高会在后续多轮扫描里逐级降回 L1，过程平滑、可观测。</p>
     *
     * @param timelineKey 帖身份（与 append 一致；有 postId 用 postId，历史数据回退 mediaId）
     * @param targetPool  目标池（必须 &lt; 当前池且 ≥ 1）
     * @return true = 已降级；false = 不在池中 / 已是更低池 / 参数非法（无需动作）
     */
    public boolean demote(String timelineKey, int targetPool) {
        if (timelineKey == null || targetPool <= 0 || targetPool >= MAX_POOL_LEVELS) {
            return false;
        }
        try {
            String idxKey = IDX_PREFIX + timelineKey;
            String location = redisTemplate.opsForValue().get(idxKey);
            if (location == null) {
                return false;
            }
            int sep = location.indexOf(IDX_SEP);
            if (sep <= 0) {
                return false;
            }
            String bucketKey = location.substring(0, sep);
            String member = location.substring(sep + IDX_SEP.length());
            String rest = bucketKey.substring(TL_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            int oldPool = Integer.parseInt(rest.substring(0, colon));
            String date = rest.substring(colon + 1);
            if (oldPool <= targetPool) {
                return false;                       // 仅降不升，避免来回抖动
            }
            Double score = redisTemplate.opsForZSet().score(bucketKey, member);
            if (score == null) {
                return false;                       // 索引与 ZSET 不一致，放弃降级
            }
            String newBucket = TL_PREFIX + targetPool + ":" + date;
            redisTemplate.opsForZSet().remove(bucketKey, member);
            redisTemplate.opsForZSet().add(newBucket, member, score);
            redisTemplate.expire(newBucket, BUCKET_TTL);
            // 反查索引改写到新桶位置：否则后续 remove 会 ZREM 旧桶（已空），下架失效
            redisTemplate.opsForValue().set(idxKey, newBucket + IDX_SEP + member, BUCKET_TTL);
            return true;
        } catch (Exception e) {
            log.warn("流量池降级失败（不影响主流程）: timelineKey={}, targetPool={}, {}", timelineKey, targetPool, e.getMessage());
            return false;
        }
    }

    /**
     * 读取某帖当前所在流量池（0 = 不在任何池中 / 已过期）。晋级器据此决定目标池，
     * 并据此把"已离开公域"的帖从待评估集合剔除。
     */
    public int currentPool(String timelineKey) {
        if (timelineKey == null) {
            return 0;
        }
        try {
            String location = redisTemplate.opsForValue().get(IDX_PREFIX + timelineKey);
            if (location == null) {
                return 0;
            }
            int sep = location.indexOf(IDX_SEP);
            if (sep <= 0) {
                return 0;
            }
            String rest = location.substring(0, sep).substring(TL_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return 0;
            }
            return Integer.parseInt(rest.substring(0, colon));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 按反查索引把内容从原桶摘除，并按需删除索引本身。
     *
     * <p>{@code append} 内部复用它做「先摘旧位置」——此时传 {@code null} 只摘除、保留索引，
     * 随后的写入会覆盖为新位置。</p>
     *
     * @param idxKeyToDelete 需要一并删除的索引 key；{@code null} 表示保留
     * @return 是否命中索引（false 表示该内容没有索引，调用方可决定是否退回扫描）
     */
    private boolean detach(String timelineKey, String idxKeyToDelete) {
        if (timelineKey == null) {
            return false;
        }
        String idxKey = IDX_PREFIX + timelineKey;
        String location = redisTemplate.opsForValue().get(idxKey);
        if (location == null) {
            return false;
        }
        int sep = location.indexOf(IDX_SEP);
        if (sep > 0) {
            redisTemplate.opsForZSet().remove(location.substring(0, sep),
                    location.substring(sep + IDX_SEP.length()));
        }
        if (idxKeyToDelete != null) {
            redisTemplate.delete(idxKeyToDelete);
        }
        return true;
    }

    /**
     * 反查索引缺失时的尽力扫描（严格路径使用）：仅覆盖每桶最新 {@code PER_BUCKET_CAP} 条。
     *
     * <p><b>严格语义</b>：不像 fail-open 版本那样吞掉基础设施异常 —— Redis 读取 / ZREM 失败必须上抛，
     * 否则消费者会误以为处理成功而 ACK，下架被静默丢失（与本方法存在的意义相悖）。
     * 只对「单条成员串 JSON 损坏」保持容忍：那是数据问题，重试不会变好，跳过该条继续扫其余。</p>
     */
    private void scanRemoveStrict(String timelineKey) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = TL_PREFIX + pool + ":" + date;
                Set<String> jsons = redisTemplate.opsForZSet()
                        .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                if (jsons == null) {
                    continue;
                }
                for (String json : jsons) {
                    FeedItemView it;
                    try {
                        it = objectMapper.readValue(json, FeedItemView.class);
                    } catch (JsonProcessingException e) {
                        // 单条成员串损坏：数据问题而非基础设施故障，重试无意义 → 跳过继续
                        log.warn("时间线成员串损坏，跳过该条: key={}, {}", key, e.getMessage());
                        continue;
                    }
                    // 用帖身份比对（历史单图成员串没有 postId，timelineKey() 会回退到 mediaId），
                    // 因此新旧数据都能被同一次扫描命中，不需要分两套匹配逻辑。
                    if (timelineKey.equals(it.timelineKey())) {
                        redisTemplate.opsForZSet().remove(key, json);
                    }
                }
            }
        }
    }
}
