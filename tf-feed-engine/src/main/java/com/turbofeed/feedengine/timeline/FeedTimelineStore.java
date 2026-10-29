package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.feedengine.contract.ItemViewCodec;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.RecommendedItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.AUTHOR_PREFIX;
import static com.turbofeed.feedengine.timeline.TimelineSupport.BUCKET_TTL;
import static com.turbofeed.feedengine.timeline.TimelineSupport.IndexEntry;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MAX_POOL_LEVELS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MERGE_BUCKETS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.PER_BUCKET_CAP;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdFromKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.bucketKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.dateOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.dateOfBucket;
import static com.turbofeed.feedengine.timeline.TimelineSupport.indexKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.indexValue;
import static com.turbofeed.feedengine.timeline.TimelineSupport.parseIndex;
import static com.turbofeed.feedengine.timeline.TimelineSupport.poolOfBucket;

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
    // PER_BUCKET_CAP / BUCKET_TTL / IDX_SEP 已上移到 TimelineSupport：它们约束的是桶与反查索引的
    // 格式本身（读路径与扫描路径共用），留在本类会让「读写各一份」重新长出来（P1-1）。
    /** 推荐流是否按流量池权重分配每页槽位（抖音式曝光分层）。false 退化为"全池合并+全局倒序"。 */
    @Value("${turbofeed.feed.pool-read-weight-enabled:true}")
    private boolean poolReadWeightEnabled;

    /**
     * 推荐漏斗编排（A7）：本类取好池候选后交给它组织成页。
     * 放在最后注入是因为它是"策略层"，依赖本类不持有的召回/排序/重排服务。
     */
    private final RecommendationPipeline recommendationPipeline;

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
        String key = bucketKey(pool, dateOf(approvedAt));
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
        redisTemplate.opsForValue().set(indexKey(timelineKey), indexValue(key, member), BUCKET_TTL);
        // 关注流作者索引（G6）：denormalized 同成员串，score = 入流时刻；关注召回依此取作者近期内容。
        String authorId = authorIdOf(item);
        if (authorId != null) {
            redisTemplate.opsForZSet().add(AUTHOR_PREFIX + authorId, member, approvedAt.toEpochMilli());
            redisTemplate.expire(AUTHOR_PREFIX + authorId, BUCKET_TTL);
        }
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
     * @param userId 个性化用户（已登录；匿名为 {@code null}）；{@code null} 或画像为空时退化为纯「入流时刻 + 完播率」排序
     * @param page   页码（从 0 开始）
     * @param size   单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    /**
     * 读一页公域发现流（读模型门面）：按流量池取候选，再交给 {@link RecommendationPipeline} 编排漏斗。
     *
     * <p>本类只做两件读模型该做的事——<b>按池取候选</b>与<b>游标</b>；"怎么把候选组织成一页"
     * 属于推荐策略，已整体迁出（A7）。两者边界：store 不知道召回配比与排序策略，
     * pipeline 不读 Redis 时间线。</p>
     *
     * <p>{@code pool-read-weight-enabled=false} 时走回退路径：全池合并 + 按入流时刻全局倒序
     * （等价按池权重改造前的语义），不进漏斗。</p>
     *
     * @param userId 当前用户（{@code null} 为匿名）
     * @param page   页序号（从 0 起）
     * @param size   单页条数（&lt;=0 取默认 20）
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<RecommendedItem> readPage(String userId, int page, int size) {
        int limit = size <= 0 ? 20 : size;
        return readPageInternal(userId, page, limit, null);
    }

    /**
     * 游标分页读取公域发现流（入流时间倒序，keyset）。
     *
     * <p>以游标 score 为<b>排他上界</b>收集各池候选（ZSET 直接 seek，O(log N)，无 OFFSET）；
     * 漏斗只取窗口内 top {@code limit}，多路召回也只取早于游标者，保证翻页单调前进、跨页不重复。
     * {@code cursor == null} 时退化为与 {@link #readPage(String, int, int)} 完全等价的页码分页。</p>
     *
     * @param userId 当前用户（{@code null} 为匿名）
     * @param limit  单页条数（&lt;=0 取默认 20）
     * @param cursor 续拉游标（{@code null} = 首页）
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<RecommendedItem> readPage(String userId, int limit, FeedCursor cursor) {
        return readPageInternal(userId, 0, limit <= 0 ? 20 : limit, cursor);
    }

    private List<RecommendedItem> readPageInternal(String userId, int page, int limit, FeedCursor cursor) {
        double maxScore = (cursor == null) ? Double.POSITIVE_INFINITY : cursor.score();
        // 先按池收集近 N 天、每池最新的候选（保留 ZSET score 以便回退路径按入流时刻排序）。
        // 游标模式以 maxScore 为上界（含），漏斗/合并阶段再精确排除游标所指项。
        Map<Integer, List<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
            byPool.put(pool, new ArrayList<>());
        }
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = dateOf(today.minusDays(d));
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = bucketKey(pool, date);
                try {
                    Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                            .reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY,
                                    maxScore, 0, PER_BUCKET_CAP);
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
            List<FeedItemView> items = new ArrayList<>(merged.size());
            for (ZSetOperations.TypedTuple<String> tuple : merged) {
                FeedItemView it = ItemViewCodec.parse(objectMapper, tuple == null ? null : tuple.getValue());
                if (it == null) {
                    continue;
                }
                // keyset：跳过游标所指项及之后（score 晚于/等于游标且身份不早于游标者）
                if (cursor != null && cursor.excludes(
                        tuple.getScore() == null ? 0d : tuple.getScore(), it.timelineKey())) {
                    continue;
                }
                items.add(it);
            }
            int from;
            int to;
            if (cursor != null) {
                from = 0;
                to = Math.min(limit, items.size());
            } else {
                long offset = (long) Math.max(page, 0) * limit;
                from = (int) Math.min(offset, items.size());
                to = (int) Math.min(offset + limit, items.size());
            }
            // 回退路径（pool-read-weight 关闭）无召回归因：item 原样返回，channel/pool 置 null
            return items.subList(from, to).stream()
                    .map(it -> new RecommendedItem(it, null, null)).toList();
        }
        return recommendationPipeline.recommend(userId, page, limit, byPool, cursor);
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
        if (detach(timelineKey, indexKey(timelineKey))) {
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
        return relevel(timelineKey, targetPool, Direction.UP);
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
        return relevel(timelineKey, targetPool, Direction.DOWN);
    }

    /** 搬池方向。晋级与降级只差这一点差异，其余逻辑必须共用（见 {@link #relevel}）。 */
    private enum Direction {
        UP, DOWN
    }

    /**
     * 搬池的<b>唯一实现</b>：把某帖从所在桶搬到同一日期、不同池号的桶。
     *
     * <p><b>为什么合并</b>：此前 {@link #promote} 与 {@link #demote} 是两份逐行镜像的方法
     * （连注释都对称），方向只体现在 {@code oldPool >= targetPool} 与 {@code <=} 一处。
     * 改一边漏一边<b>不会有任何编译或测试信号</b>——搬池失败只表现为「内容没被放大 / 没被压下去」，
     * 属于纯静默的软故障。收成一份后，方向只是入参。</p>
     *
     * <p><b>两条路径共用的不变量</b>：</p>
     * <ol>
     *   <li>score（入流时刻）不因搬池改变——变的只是曝光分层，排序语义不受影响；</li>
     *   <li>反查索引必须同步改写到新桶，否则后续 {@link #remove} 会 ZREM 已空的旧桶，下架失效；</li>
     *   <li>索引缺失或格式非法 → 放弃（无从定位，不猜）；{@code ZSCORE} 取不到 → 放弃（索引与
     *       ZSET 已不一致，猜一个 score 会把内容写到错误的时序位置）。</li>
     * </ol>
     *
     * <p>与合并前的一处微小差异：原 {@code demote} 对 {@code targetPool == MAX_POOL_LEVELS}
     * 直接拒绝、不读索引；现交由方向判断覆盖，结论相同，只多一次 O(1) GET。</p>
     */
    private boolean relevel(String timelineKey, int targetPool, Direction direction) {
        if (timelineKey == null || targetPool < 1 || targetPool > MAX_POOL_LEVELS) {
            return false;
        }
        try {
            String idxKey = indexKey(timelineKey);
            IndexEntry entry = parseIndex(redisTemplate.opsForValue().get(idxKey));
            if (entry == null) {
                return false;                       // 未入池 / 索引值格式非法，无从搬起
            }
            int oldPool = poolOfBucket(entry.bucketKey());
            String date = dateOfBucket(entry.bucketKey());
            if (oldPool < 0 || date == null) {
                // 桶 key 不是本类格式：不硬抠池号（抠出来的数字会指向一个真实存在的桶，
                // 于是「挪错桶」被当成成功写下去）
                return false;
            }
            boolean wrongDirection = direction == Direction.UP
                    ? oldPool >= targetPool
                    : oldPool <= targetPool;
            if (wrongDirection) {
                return false;                       // 只朝一个方向走，避免来回抖动
            }
            Double score = redisTemplate.opsForZSet().score(entry.bucketKey(), entry.member());
            if (score == null) {
                return false;                       // 索引与 ZSET 不一致，放弃搬池
            }
            String newBucket = bucketKey(targetPool, date);
            redisTemplate.opsForZSet().remove(entry.bucketKey(), entry.member());
            redisTemplate.opsForZSet().add(newBucket, entry.member(), score);
            redisTemplate.expire(newBucket, BUCKET_TTL);
            // 反查索引改写到新桶位置：否则后续 remove 会 ZREM 旧桶（已空），下架失效
            redisTemplate.opsForValue().set(idxKey, indexValue(newBucket, entry.member()), BUCKET_TTL);
            return true;
        } catch (Exception e) {
            log.warn("流量池搬池失败（不影响主流程）: timelineKey={}, targetPool={}, direction={}, {}",
                    timelineKey, targetPool, direction, e.getMessage());
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
            IndexEntry entry = parseIndex(redisTemplate.opsForValue().get(indexKey(timelineKey)));
            if (entry == null) {
                return 0;
            }
            int pool = poolOfBucket(entry.bucketKey());
            return pool < 0 ? 0 : pool;         // -1（格式非法）与 0（不在池中）对调用方同义
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 取某内容的<b>入流排序位</b>（{@code approvedAt} 的 epoch 毫秒，即它在时间线桶里的 score）。
     *
     * <p><b>为什么需要这个读口</b>：标签倒排的 SET 版本与 ZSET 版本是分批上线的——先有 SET，
     * ZSET 是搜索上线时才补的。于是<b>存量内容只有 SET 成员、没有 ZSET 分数</b>：搜索结果
     * {@code ZREVRANGEBYSCORE} 读不到它们，表现为「老帖搜不到」。回填时唯一的权威分数来源
     * 就是这里——反查索引 {@code tf:feed:idx:{timelineKey}} 指出所在桶与成员串，再
     * {@code ZSCORE} 取回入流时刻。</p>
     *
     * <p><b>为什么不用 {@code FeedItemView} 里的时间字段</b>：入流时间由 {@code appendStrict}
     * 用 {@code Instant.now()} 生成，<b>不来自条目本身</b>；条目上的创建时间与入流时间可能
     * 相差一次审核往返。回填必须与线上写入口径完全一致，否则同一话题下新旧内容的相对顺序会错。</p>
     *
     * @param timelineKey 内容身份（postId，历史数据回退 mediaId）
     * @return 入流时刻毫秒；未入流 / 索引已过期 / 反查索引损坏时返回 {@code null}
     *         （调用方据此跳过该成员——它本来也不在可服务窗口内）
     */
    public Long inflowScoreOf(String timelineKey) {
        if (timelineKey == null || timelineKey.isBlank()) {
            return null;
        }
        try {
            IndexEntry entry = parseIndex(redisTemplate.opsForValue().get(indexKey(timelineKey)));
            if (entry == null) {
                return null;
            }
            Double score = redisTemplate.opsForZSet().score(entry.bucketKey(), entry.member());
            return score == null ? null : score.longValue();
        } catch (Exception e) {
            log.warn("读入流排序位失败（回填时跳过该条）: timelineKey={}, {}", timelineKey, e.getMessage());
            return null;
        }
    }

    /**
     * 按反查索引把内容从原桶摘除，并按需删除索引本身。
     *
     * <p>{@code append} 内部复用它做「先摘旧位置」——此时传 {@code null} 只摘除、保留索引，
     * 随后的写入会覆盖为新位置。</p>
     *
     * <p><b>P1-1 修正</b>：索引<b>存在但格式非法</b>时，原先仍返回 {@code true}（只删索引、不摘桶），
     * 于是 {@link #removeStrict} 认为「已精确下架」而不再退回扫描 —— 内容实际还在桶里，
     * 表现为「下架成功但内容仍可见」。现改为返回 {@code false}，让它退回尽力扫描。</p>
     *
     * @param idxKeyToDelete 需要一并删除的索引 key；{@code null} 表示保留
     * @return 是否命中<b>可用的</b>索引（false 表示索引缺失或格式非法，调用方应退回扫描）
     */
    private boolean detach(String timelineKey, String idxKeyToDelete) {
        if (timelineKey == null) {
            return false;
        }
        String idxKey = indexKey(timelineKey);
        IndexEntry entry = parseIndex(redisTemplate.opsForValue().get(idxKey));
        if (entry == null) {
            return false;
        }
        redisTemplate.opsForZSet().remove(entry.bucketKey(), entry.member());
        // 同步摘除关注流作者索引（G6）：同成员串，避免已下架内容仍出现在关注流
        String aid = authorIdFromKey(timelineKey);
        if (aid != null) {
            redisTemplate.opsForZSet().remove(AUTHOR_PREFIX + aid, entry.member());
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
            String date = dateOf(today.minusDays(d));
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = bucketKey(pool, date);
                Set<String> jsons = redisTemplate.opsForZSet()
                        .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                if (jsons == null) {
                    continue;
                }
                for (String json : jsons) {
                    FeedItemView it = ItemViewCodec.parse(objectMapper, json);
                    if (it == null) {
                        // 格式损坏 / 版本不兼容：数据问题而非基础设施故障，重试无意义 → 跳过继续。
                        // 走统一入口而非裸 readValue：本处原是 4 处绕过版本协商的旁路之一（M3 收口）。
                        log.warn("时间线成员串不可用（格式损坏或版本不兼容），跳过该条: key={}", key);
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
