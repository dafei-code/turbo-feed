package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.RankingFeatures;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.feedengine.ranking.PreRankCandidate;
import com.turbofeed.feedengine.ranking.PreRankContext;
import com.turbofeed.feedengine.ranking.PreRankFilter;
import com.turbofeed.feedengine.ranking.RealtimeFeatureService;
import com.turbofeed.feedengine.ranking.UserRealtimeProfile;
import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.feedengine.social.FollowService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /** 参与排序的兴趣标签取 TopN（控制 HGETALL 后排序成本，且避免长尾标签噪声）。 */
    private static final int INTEREST_TOP_N = 30;
    /** 参与排序的 session 序列取最近 N 条（窗口大小；与画像 TopN 解耦）。 */
    private static final int SESSION_TOP_N = 20;
    private final PostStatService postStatService;
    private final InterestService interestService;
    /** session 级行为序列（最近互动），用于精排的"跟手"信号（见 {@link SessionSequenceService}）。 */
    private final SessionSequenceService sessionSequenceService;
    /**
     * 精排打分器（可插拔）。打分从本类抽到 {@link RankingModel}：读路径只负责"取候选 + 组装特征"，
     * 怎么打分由模型决定——将来换双塔 / 精排模型或接远程推理，都不必再动这段最热的读代码。
     */
    private final RankingModel rankingModel;
    /**
     * 审核信号读取客户端（抖音式「审核与推荐解耦」：推荐侧直读 {@code tf:mod:} KV）。
     * 召回层用它过滤 INTERCEPT/MONITOR 内容与低健康分作者；排序层经 {@link #scoreOf} 懒读作者健康分降权。
     */
    private final ReviewSignalClient reviewSignalClient;
    /** 推荐侧消费审核信号的开关与阈值（namespace / 召回过滤 / 降权系数 / 剔除阈值）。 */
    private final FeedModerationProperties moderationProperties;
    /**
     * 向量召回通道（抖音式多路召回第四路）：用户向量 × 内容向量余弦相似度 TopN。
     * 与热点/兴趣/流量池三路并列，捞"语义相近但未必同标签"的隐式兴趣内容。
     */
    private final VectorRecallChannel vectorRecallChannel;
    /** 实时特征聚合（抖音式「实时特征流」）：每页算一次，喂给排序模型。 */
    private final RealtimeFeatureService realtimeFeatureService;
    /**
     * 粗排截断过滤器（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。
     * 对流量池全量候选用廉价特征快速打分、只保留 Top-N 进精排，省算力、防长尾淹没。
     * 默认关闭（{@code preRankKeepRatio=0}），开启后对池候选做截断。
     */
    private final PreRankFilter preRankFilter;

    private static final String TL_PREFIX = "tf:feed:tl:";
    /** 反查索引前缀：帖身份（postId，历史数据回退 mediaId）-&gt; 所在桶 key + 成员串（用于精确 ZREM）。 */
    private static final String IDX_PREFIX = "tf:feed:idx:";
    /** 作者内容索引前缀（关注流 G6 召回用）：authorId -&gt; ZSET(成员串 → 入流时刻毫秒)。 */
    private static final String AUTHOR_PREFIX = "tf:feed:author:";
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
    /** 兴趣召回的过量取倍数：过滤掉与热点/流量池重复的后仍要够填满槽位。 */
    private static final int RECALL_OVERFETCH = 3;
    /** 关注流召回：单次最多扫描的关注作者数（限制 Redis 往返，避免大 V 关注爆炸）。 */
    private static final int FOLLOW_SCAN_LIMIT = 50;
    /** 关注流召回：每位关注作者最多取其近期已审内容条数。 */
    private static final int FOLLOW_PER_AUTHOR = 3;

    /**
     * 兴趣召回位的时间序取值：以<b>内容创建时间</b>作代理。
     *
     * <p>时间线 ZSET 的 score 是"入流时刻（过审时刻）"，但兴趣召回的内容来自标签索引，
     * 不必然落在当前合并窗口的分桶里，因此拿不到它的入流 score。
     * 创建时间与入流时刻同为 epoch 毫秒、量纲一致，作为代理是安全的——
     * 且召回的内容受标签索引 7 天 TTL 约束，二者相差有限。</p>
     */
    private static double recencyOf(FeedItemView item) {
        if (item == null || item.createdAt() == null) {
            return 0d;
        }
        return item.createdAt().toEpochMilli();
    }

    /** 推荐流是否按流量池权重分配每页槽位（抖音式曝光分层）。false 退化为"全池合并+全局倒序"。 */
    @Value("${turbofeed.feed.pool-read-weight-enabled:true}")
    private boolean poolReadWeightEnabled;
    /** 各流量池在推荐流中的曝光权重（按池序 L1..Ln，默认 10%/30%/60%：新内容试水/已验证优质占大头）。 */
    @Value("${turbofeed.feed.pool-weights:0.1,0.3,0.6}")
    private String poolWeightsCsv;
    /**
     * 热点召回占每页槽位的比例（抖音式"热门"独立通路）。
     * {@code 0} = 关闭，读路径退化为改造前语义（仅按流量池权重切片）。
     */
    @Value("${turbofeed.feed.hot-recall-ratio:0.1}")
    private double hotRecallRatio;
    /**
     * 兴趣召回占每页槽位的比例（抖音式多路召回里的"兴趣召回"这一路）。
     * {@code 0} = 关闭，读路径退化为「流量池 + 热点」两路。
     *
     * <p>与 {@link #hotRecallRatio} 的关系：两者都是"给某路召回留固定预算"，
     * 差别在语义——热点是<b>全站探索位</b>（人人相似，用来试探爆款），
     * 兴趣是<b>个性化位</b>（因人而异，用来兑现画像）。槽位从同一份 {@code limit} 里切，
     * 各自独立配置，剩余才归流量池，保证单页总量不超 {@code limit}。</p>
     */
    @Value("${turbofeed.feed.interest-recall-ratio:0.15}")
    private double interestRecallRatio;
    /**
     * 向量召回占每页槽位的比例（抖音式多路召回里的"向量/双塔召回"这一路）。
     * {@code 0} = 关闭，读路径退化为「热点 + 兴趣 + 流量池」三路。
     */
    @Value("${turbofeed.feed.vector-recall-ratio:0.1}")
    private double vectorRecallRatio;
    /**
     * 关注流召回占每页槽位的比例（抖音式「关注流 / 社交分发」G6，推荐消费侧）。
     * {@code 0} = 关闭，读路径退化为「热点 + 兴趣 + 向量 + 流量池」，不做社交分发。
     */
    @Value("${turbofeed.feed.follow-recall-ratio:0.1}")
    private double followRecallRatio;
    /**
     * 重排多样性配置（抖音式「同类不连刷 + 作者去重 + 标签打散」上下文感知重排 G8）。
     * 取代原先单一的 {@code diversity-window} 二进制打散，升级为贪心 listwise 重排。
     */
    private final DiversityRerankProperties diversityRerankProperties;
    /** 上下文感知重排服务（G8）：贪心 listwise 重排，强化作者去重与标签多样性，fail-open 回退原序。 */
    private final DiversityRerankService diversityRerankService;
    /** 冷启动探索服务（G7：EE 探索利用）——给新内容 / 低曝光内容固定曝光配额，避免饿死。 */
    private final ColdStartService coldStartService;
    /** 关注关系读取（G6：抖音式「关注流 / 社交分发」推荐消费侧，读 tf:follow:{userId} 社交图）。 */
    private final FollowService followService;
    /** 生态调控层（G9：抖音式「生态调控」整页全局占比配额，与 G8 滑窗重排互补）。 */
    private final EcosystemRegulationService ecosystemRegulationService;

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
    public List<FeedItemView> readPage(String userId, int page, int size) {
        int limit = size <= 0 ? 20 : size;
        // 一次性取用户兴趣画像（TopN 正向标签→权重）；无画像/匿名→空 Map，走冷启动排序。
        // 长期层（定"你是什么样的人"）用于 interestMatch；短期层（定"你现在想要什么"）
        // 单独取出用于 shortTermMatch，二者在 RankingModel 里叠加——这是短期兴趣"排最前"的落点。
        Map<String, Double> interest = (userId == null || !interestService.hasInterest(userId))
                ? Map.of() : interestService.weightedTags(userId, INTEREST_TOP_N);
        Map<String, Double> shortInterest = (userId == null || !interestService.hasInterest(userId))
                ? Map.of() : interestService.shortTermTags(userId, INTEREST_TOP_N);
        // session 级最近互动序列（一次取出，scoreOf 内逐候选复用，避免每个候选各查一次 Redis）。
        List<SessionSequenceService.SessionItem> session = (userId == null)
                ? List.of() : sessionSequenceService.recent(userId, SESSION_TOP_N);
        // 负向标签（抖音式「不感兴趣 → 对该用户打压同标签内容」）。匿名 / 无负反馈为空集，
        // 与画像解耦：即使该用户画像为空（冷启动）也照样生效——负反馈不需要先有正反馈。
        Set<String> negative = interestService.negativeTags(userId);
        // 实时特征画像（抖音式「实时特征流」）：每页只算一次，喂排序模型（见 RealtimeFeatureService）。
        UserRealtimeProfile realtimeProfile = realtimeFeatureService.profileOf(userId);
        // 作者健康分排序系数懒加载缓存（抖音式「审核与推荐解耦」：scoreOf 内按 authorId 读 tf:mod:account 并缓存本页）。
        Map<String, Double> authorHealthCache = new LinkedHashMap<>();
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
        // ==================================================================
        // 热点召回（抖音式"热门"独立通路）：先按热度榜取 Top-N 内容身份，再经反查索引
        // 还原成完整条目。它是流量池之外的<b>第二路召回</b>，单独占 hot-recall-ratio 的槽位，
        // 流量池只参与剩下的 (limit - hotCount) 个槽位分配，保证单页总量始终不超 limit。
        // ==================================================================
        int hotSlots = hotRecallRatio > 0d
                ? (int) Math.floor(limit * Math.min(hotRecallRatio, 1.0d))
                : 0;
        List<FeedItemView> hotItems = new ArrayList<>();
        Set<String> hotKeys = new LinkedHashSet<>();
        if (hotSlots > 0) {
            for (String tk : postStatService.hotTimelineKeys(hotSlots)) {
                FeedItemView hot = memberOf(tk);
                if (hot != null && hotKeys.add(tk)) {
                    hotItems.add(hot);
                }
            }
        }

        // ==================================================================
        // 兴趣召回（抖音式多路召回的第三路）：按用户 TopN 兴趣标签经「标签→内容」索引取候选。
        //
        // <b>只排除热点槽位，不排除流量池候选</b>——这是刻意的：
        // 若把流量池全部候选都排除，召回就只剩"3 天合并窗口之外"的老内容可捞，
        // 而召回真正想捞的恰恰是"在池里但排不进当前页"的同类好内容
        // （池是按入流时刻切片的，深页内容永远翻不到）。
        // 重复问题交给下面流量池解析时按 interestKeys 去重解决——谁先占位谁算，
        // 最终单页不出现重复条目，且个性化位优先。
        // ==================================================================
        int interestSlots = (userId != null && interestRecallRatio > 0d && !interest.isEmpty())
                ? (int) Math.floor(limit * Math.min(interestRecallRatio, 1.0d))
                : 0;
        List<ScoredItem> interestPart = new ArrayList<>();
        Set<String> interestKeys = new LinkedHashSet<>();
        if (interestSlots > 0) {
            // 过量取：过滤掉与热点重复的后还能填满槽位
            List<String> recalled = interestService.recallTimelineKeys(
                    userId, interestSlots * RECALL_OVERFETCH + 4);
            for (String tk : recalled) {
                if (interestPart.size() >= interestSlots) {
                    break;
                }
                if (tk == null || !interestKeys.add(tk) || hotKeys.contains(tk)) {
                    continue;
                }
                FeedItemView it = memberOf(tk);
                if (it == null) {
                    continue;
                }
                interestPart.add(new ScoredItem(it, recencyOf(it)));
            }
            // 个性化位内部仍按精排分排序：召回只保证"这类内容进得来"，
            // 不保证"质量差的也往前放"——排序权交给 RankingModel（含作者健康分降权）。
            interestPart.sort((a, b) -> Double.compare(scoreOf(b, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile),
                    scoreOf(a, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile)));
        }

        Map<Integer, List<FeedItemView>> parsed = new LinkedHashMap<>();
        Map<Integer, List<ScoredItem>> scored = new LinkedHashMap<>();
        // 向量召回位去重集（声明在池解析之前以固定作用域；池解析阶段引用它为 null-safe 空集，
        // 实际去重在向量召回计算之后、流量池切片构造之前才生效）。
        List<ScoredItem> vectorPart = new ArrayList<>();
        Set<String> vectorKeys = new LinkedHashSet<>();
        for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
            List<FeedItemView> l = new ArrayList<>();
            List<ScoredItem> sl = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> t : byPool.get(pool)) {
                FeedItemView it = parseMember(t == null ? null : t.getValue());
                if (it == null) {
                    continue;
                }
                // 同页去重：已在热点槽位 / 兴趣召回位露出的内容不再占流量池槽位——
                // 否则同一页会重复出现同一条内容，还白白吃掉一个曝光位。
                if (hotKeys.contains(it.timelineKey()) || interestKeys.contains(it.timelineKey())
                        || vectorKeys.contains(it.timelineKey())) {
                    continue;
                }
                l.add(it);
                double recency = t != null && t.getScore() != null ? t.getScore() : 0d;
                sl.add(new ScoredItem(it, recency));
            }
            parsed.put(pool, l);
            scored.put(pool, sl);
        }

        // ==================================================================
        // 粗排截断（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。
        // 对流量池全量候选用廉价特征（兴趣/短期/session/实时 + 新鲜度，不查 DB 统计、不查健康分）
        // 快速打分，只保留 Top-N 进精排——把「海量候选 × 贵精排」压成「少量候选 × 精排」。
        // 默认关闭（preRankKeepRatio=0 → preRank 返回空集 = 不裁剪，行为与改造前一致）。
        // ==================================================================
        final Set<String> preRankKeep;
        if (preRankFilter.isEnabled()) {
            List<PreRankCandidate> poolCands = new ArrayList<>();
            for (List<ScoredItem> sl : scored.values()) {
                for (ScoredItem s : sl) {
                    poolCands.add(new PreRankCandidate(s.item(), s.recency()));
                }
            }
            int keep = Math.max(1, preRankFilter.keepCount(limit));
            preRankKeep = preRankFilter.preRank(poolCands,
                    new PreRankContext(interest, shortInterest, session, realtimeProfile), keep);
        } else {
            preRankKeep = Set.of();
        }

        // ==================================================================
        // 向量召回（抖音式多路召回的第四路）：用户向量 × 内容向量余弦相似度 TopN。
        // 与兴趣召回互补——后者是"显式同标签"，本路是"语义相近但未必同标签"的隐式兴趣。
        // 候选来自流量池全量（排除已占热点/兴趣槽位者），过量取后由通道内部按相似度截断。
        // ==================================================================
        int vectorSlots = (userId != null && vectorRecallRatio > 0d && !interest.isEmpty())
                ? (int) Math.floor(limit * Math.min(vectorRecallRatio, 1.0d))
                : 0;
        if (vectorSlots > 0) {
            List<FeedItemView> candidates = new ArrayList<>();
            for (List<FeedItemView> l : parsed.values()) {
                for (FeedItemView it : l) {
                    String tk = it == null ? null : it.timelineKey();
                    if (tk != null && !hotKeys.contains(tk) && !interestKeys.contains(tk)) {
                        candidates.add(it);
                    }
                }
            }
            List<FeedItemView> recalled = vectorRecallChannel.recall(userId,
                    candidates, vectorSlots * RECALL_OVERFETCH + 4);
            for (FeedItemView it : recalled) {
                if (vectorPart.size() >= vectorSlots) {
                    break;
                }
                if (it == null) {
                    continue;
                }
                String tk = it.timelineKey();
                if (tk == null || !vectorKeys.add(tk) || hotKeys.contains(tk) || interestKeys.contains(tk)) {
                    continue;
                }
                vectorPart.add(new ScoredItem(it, recencyOf(it)));
            }
            // 同个性化位，内部仍按精排分排序（召回只保证"进得来"，排序权交给 RankingModel）。
            vectorPart.sort((a, b) -> Double.compare(
                    scoreOf(b, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile),
                    scoreOf(a, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile)));
        }

        // ==================================================================
        // 关注流召回（抖音式「关注流 / 社交分发」G6，推荐消费侧）：读 tf:follow:{userId} 社交图，
        // 把关注作者的近期已审内容混入发现流。它是流量池之外的<b>社交分发通路</b>，单独占
        // follow-recall-ratio 的槽位；与热点/兴趣/向量并列，剩余归流量池。
        // 关注关系缺失 / 无关注 → 空，发现流退化为纯公域（fail-open 不阻断浏览）。
        // ==================================================================
        int followSlots = (userId != null && followRecallRatio > 0d && followService.hasFollows(userId))
                ? (int) Math.floor(limit * Math.min(followRecallRatio, 1.0d))
                : 0;
        List<ScoredItem> followPart = new ArrayList<>();
        Set<String> followKeys = new LinkedHashSet<>();
        if (followSlots > 0) {
            int budget = followSlots * RECALL_OVERFETCH + 4;
            for (String aid : followService.followedAuthors(userId, FOLLOW_SCAN_LIMIT)) {
                if (followPart.size() >= followSlots) {
                    break;
                }
                for (FeedItemView it : authorRecentItems(aid, FOLLOW_PER_AUTHOR)) {
                    if (followPart.size() >= followSlots) {
                        break;
                    }
                    String tk = it == null ? null : it.timelineKey();
                    if (tk == null || !followKeys.add(tk) || hotKeys.contains(tk)
                            || interestKeys.contains(tk) || vectorKeys.contains(tk)) {
                        continue;
                    }
                    followPart.add(new ScoredItem(it, recencyOf(it)));
                }
            }
            // 社交位内部仍按精排分排序（召回只保证"进得来"，排序权交给 RankingModel）。
            followPart.sort((a, b) -> Double.compare(
                    scoreOf(b, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile),
                    scoreOf(a, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile)));
        }

        int remainingSlots = Math.max(limit - hotItems.size() - followPart.size() - interestPart.size() - vectorPart.size(), 0);
        int[] alloc = allocateSlots(remainingSlots, weights, parsed);
        long pageNum = Math.max(page, 0);
        List<FeedItemView> result = new ArrayList<>(limit);

        // 流量池部分先全量收集，最后统一走一次"同类不连刷"重排——在<b>整页范围</b>内打散，
        // 而不是每个池各自打散：否则相邻两池的交界处仍会连着刷同一个话题。
        List<ScoredItem> poolPart = new ArrayList<>();
        for (int pool = MAX_POOL_LEVELS; pool >= 1; pool--) {
            List<ScoredItem> items = scored.get(pool);
            int per = alloc[pool - 1];
            if (per <= 0 || items.isEmpty()) {
                continue;
            }
            // 分页游标：本池第 page 页应取 [per*page, per*(page+1))。
            // 修复前是 per*page*limit（多乘了一次页大小）：page=1 时从 240 条开始切，
            // 第 0 页之后的大量内容被整段跳过，深池内容永远翻不到——这是原先
            // "翻页翻着翻着就没了"的直接原因。
            int start = (int) Math.min(per * pageNum, items.size());
            int end = Math.min(start + per, items.size());
            if (start >= end) {
                continue;
            }
            // 池内按"入流时刻 + 完播率加权"重排：完播率高的内容在同类 cohort 里往前排（抖音式"看完即加权"）。
            // 仅对当页切片重排，Redis 读次数有界（≤ 本池页大小），不扫全量候选。
            List<ScoredItem> slice = new ArrayList<>(items.subList(start, end));
            // 粗排截断：仅保留被粗排选中的候选（preRankKeep 非空时才生效；空 = 不裁剪）。
            if (!preRankKeep.isEmpty()) {
                slice.removeIf(s -> !preRankKeep.contains(s.item().timelineKey()));
            }
            // 精排：统一交给 RankingModel（含作者健康分降权）。原先这里有一个"各加权项全为 0 就跳过排序"的开关，
            // 现在权重默认非 0 且排序对象是页级切片（≤ 页大小），成本可忽略，故恒重排——
            // 少一个分支就少一处"权重配置错了却以为在排序"的静默分歧。
            slice.sort((a, b) -> Double.compare(scoreOf(b, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile),
                    scoreOf(a, interest, shortInterest, session, negative, authorHealthCache, realtimeProfile)));
            poolPart.addAll(slice);
        }

        // ===== 审核信号接入（抖音式「审核与推荐解耦」：推荐侧直读 tf:mod: KV）=====
        // 与 P2-1 生产者对称：KV 是给推荐主流程的加速副本，读失败/缺失按 fail-open 放行，绝不阻断浏览。
        // 召回层：内容处置 INTERCEPT/MONITOR、低健康分/封禁作者 → 剔除公域发现流。
        Set<String> candidatePosts = new LinkedHashSet<>();
        Set<String> candidateAuthors = new LinkedHashSet<>();
        for (FeedItemView it : hotItems) {
            collectModerationKeys(it, candidatePosts, candidateAuthors);
        }
        for (ScoredItem s : interestPart) {
            collectModerationKeys(s.item(), candidatePosts, candidateAuthors);
        }
        for (ScoredItem s : followPart) {
            collectModerationKeys(s.item(), candidatePosts, candidateAuthors);
        }
        for (ScoredItem s : poolPart) {
            collectModerationKeys(s.item(), candidatePosts, candidateAuthors);
        }

        Set<String> blockedPosts = Set.of();
        Set<String> blockedAuthors = Set.of();
        if (moderationProperties.isEnableRecallFilter()) {
            blockedPosts = reviewSignalClient.blockedContentKeys(candidatePosts);
            blockedAuthors = reviewSignalClient.blockedAuthorIds(candidateAuthors);
        }

        // 流量池部分：统一走一次上下文感知重排（G8：在整页范围内强化作者去重 + 标签多样性），再入流。
        // G9 生态调控改到整页组装完成后做全局封顶（见下方 return 前），与「整页全局占比配额」语义一致。
        poolPart = diversityRerankService.rerank(poolPart);

        // ===== 冷启动探索池（G7：EE 探索利用）=====
        // 给新内容 / 低曝光内容固定曝光配额，避免「分数低就永远刷不到」的饿死。
        // 取候选失败 / 关闭 → 空集，不注入（fail-open 不阻断浏览）。
        int coldBudget = coldStartService.exploreBudget(limit);
        List<FeedItemView> coldPart = coldBudget > 0
                ? collectColdStartCandidates(coldBudget, coldStartService.coldThreshold(Instant.now()), coldStartService.maxPool())
                : List.of();

        // 热点槽位<b>固定钉在最前</b>（先过召回过滤）：它是召回策略刻意给的探索位，不参与后续打散，
        // 否则"热门内容靠前"的策略语义会被重排层抹掉。
        for (FeedItemView it : hotItems) {
            if (!isModerationBlocked(it, blockedPosts, blockedAuthors)) {
                result.add(it);
            }
        }
        // 关注流位紧随热门之后（同为强信号位）：热点是"全站探索"、关注是"社交分发"，都先于兴趣/向量/流量池。
        // 单列于召回层、不参与后续打散——再交给重排层会把"关注优先"的语义抹掉。
        for (ScoredItem s : followPart) {
            if (!isModerationBlocked(s.item(), blockedPosts, blockedAuthors)) {
                result.add(s.item());
            }
        }
        // 兴趣召回位紧随热点之后：热点是"全站探索"，兴趣是"个性化兑现"，都先于流量池的通用排序。
        // 不参与后续打散——召回层已按多标签轮转保证覆盖，再交给打散层重排会抹掉"兴趣优先"的语义。
        for (ScoredItem s : interestPart) {
            if (!isModerationBlocked(s.item(), blockedPosts, blockedAuthors)) {
                result.add(s.item());
            }
        }
        // 向量召回位紧随兴趣之后（同为个性化位）：热点=全站探索、兴趣/向量=个性化兑现，都先于流量池。
        for (ScoredItem s : vectorPart) {
            if (!isModerationBlocked(s.item(), blockedPosts, blockedAuthors)) {
                result.add(s.item());
            }
        }
        // 流量池部分（已打散）入流。
        for (ScoredItem s : poolPart) {
            if (!isModerationBlocked(s.item(), blockedPosts, blockedAuthors)) {
                result.add(s.item());
            }
        }
        // 冷启动探索池（G7）：把新鲜内容注入发现流末尾，去重 + 过召回过滤，保证新内容有曝光出路。
        if (!coldPart.isEmpty()) {
            Set<String> resultKeys = new HashSet<>();
            for (FeedItemView r : result) {
                String k = r.timelineKey();
                if (k != null) {
                    resultKeys.add(k);
                }
            }
            for (FeedItemView it : coldPart) {
                String tk = it.timelineKey();
                if (tk != null && resultKeys.contains(tk)) {
                    continue; // 已在热/兴趣/向量/池中，避免重复
                }
                if (isModerationBlocked(it, blockedPosts, blockedAuthors)) {
                    continue;
                }
                result.add(it);
            }
        }
        // 生态调控层（G9：抖音式「整页全局占比配额」，与 G8 滑窗重排互补）：对整页内容做全局作者/标签封顶，
        // 防单一作者/话题垄断整页；保序裁低位超额尾部，fail-open 回退原序（整页范围生效，含冷启动注入位）。
        result = ecosystemRegulationService.regulate(result, limit);
        return result;
    }

    /** 池内重排用的"内容 + 入流时刻(score)"持有体（package-private 以便 {@link DiversityRerankService} 复用）。 */
    record ScoredItem(FeedItemView item, double recency) {
    }

    // 负反馈惩罚的量级已迁至 RankingProperties#negativePenaltyMillis（打分统一归模型）。
    // 语义不变：命中用户负向标签即在其所属流量池内沉底，只降权、不删除——与内容下架
    // （{@link #remove}）是两种不同性质的动作。

    /**
     * 内容是否命中该用户的负向标签（至少一个标签相交即判定命中）。
     *
     * <p>用"相交"而非"全部匹配"：短视频的负面标签往往是场景化的（比如在"宠物"下点了不感兴趣，
     * 是因为不喜欢某类养宠内容而非所有动物），只要内容<b>沾到</b>被否定的标签就应当沉底——
     * 这与正向兴趣"累加多个标签命中分"形成对称：正向是加分累积，负向是一票否决。</p>
     */
    private static boolean matchesNegative(FeedItemView item, Set<String> negative) {
        if (negative == null || negative.isEmpty() || item.tags() == null) {
            return false;
        }
        for (String tag : item.tags()) {
            if (negative.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    // ===== 审核信号接入辅助（抖音式「审核与推荐解耦」：推荐侧直读 tf:mod: KV）=====

    /** 单条候选的键收集：postId(=timelineKey) 入 posts，authorId(从 postId 解析) 入 authors。 */
    private static void collectModerationKeys(FeedItemView it, Set<String> posts, Set<String> authors) {
        if (it == null) {
            return;
        }
        String tk = it.timelineKey();
        if (tk != null) {
            posts.add(tk);
        }
        String aid = authorIdOf(it);
        if (aid != null) {
            authors.add(aid);
        }
    }

    /** 该候选是否应被召回层剔除（内容处置 INTERCEPT/MONITOR，或作者健康分低于阈值/封禁）。 */
    private static boolean isModerationBlocked(FeedItemView it, Set<String> blockedPosts, Set<String> blockedAuthors) {
        String tk = it.timelineKey();
        if (tk != null && blockedPosts.contains(tk)) {
            return true;
        }
        String aid = authorIdOf(it);
        return aid != null && blockedAuthors.contains(aid);
    }

    /**
     * 从条目身份解析作者 userId：postId 形如 {@code post/{userId}/{uuid}}（历史单图回退 mediaId
     * {@code media/{userId}/{uuid}.ext}），取首段斜杠后的 userId。与网关写入 {@code tf:mod:account:{userId}}
     * 的键约定对齐；解析失败返回 {@code null}（fail-open：不剔除、不降权）。
     */
    static String authorIdOf(FeedItemView item) {
        return authorIdFromKey(item == null ? null : item.timelineKey());
    }

    /**
     * 从帖身份解析作者 userId：postId 形如 {@code post/{userId}/{uuid}}（历史单图回退 mediaId
     * {@code media/{userId}/{uuid}.ext}），取首段斜杠后的 userId。与网关写入 {@code tf:mod:account:{userId}}
     * 的键约定对齐；解析失败返回 {@code null}（fail-open：不剔除、不降权）。
     */
    private static String authorIdFromKey(String pk) {
        if (pk == null || pk.isEmpty()) {
            return null;
        }
        int first = pk.indexOf('/');
        if (first < 0) {
            return null;
        }
        int second = pk.indexOf('/', first + 1);
        return second > 0 ? pk.substring(first + 1, second) : null;
    }

    /**
     * 组装一条候选的排序特征并交给 {@link RankingModel} 打分（<b>本类不再内含打分公式</b>）。
     *
     * <p>公式、权重与置信度平滑全部在 {@link com.turbofeed.feedengine.ranking.LinearWeightedRankingModel}
     * 与 {@link com.turbofeed.feedengine.ranking.RankingProperties} 里，此处只做三件事：
     * <ol>
     *   <li>取该内容的实时分（曝光 / 完播 / 点赞 / 评论 / 分享 / 踩）；</li>
     *   <li>按内容标签与用户画像算兴趣匹配分（封顶交给模型）；</li>
     *   <li>判定是否命中该用户的负向标签（{@link #matchesNegative}）。</li>
     * </ol>
     * 之所以传<b>原始计数</b>而非现成比率：比率一旦算出就把样本量信息丢了，
     * "1 次曝光的 100%" 与 "万次曝光的 98%" 必须能被模型区分（置信度平滑的前提）。</p>
     *
     * <p>fail-open：统计读不到时按零计数处理——平滑后自然回落到先验水平，
     * 而不是朴素比率的 0 分，新内容不至于被一次性地压到队尾。</p>
     */
    private double scoreOf(ScoredItem s, Map<String, Double> interest,
                            Map<String, Double> shortInterest,
                            List<SessionSequenceService.SessionItem> session, Set<String> negative,
                            Map<String, Double> authorHealthCache, UserRealtimeProfile realtimeProfile) {
        PostStatService.PostStat stat = new PostStatService.PostStat(0, 0, 0, 0, 0, 0);
        try {
            stat = postStatService.snapshot(s.item().timelineKey());
        } catch (Exception ignore) {
            // 统计不可用：按零计数处理——平滑后自然回落到先验水平，而不是朴素比率的 0
        }
        double interestScore = 0d;
        double shortTermScore = 0d;
        if (!interest.isEmpty()) {
            List<String> tags = s.item().tags();
            if (tags != null) {
                for (String tag : tags) {
                    Double w = interest.get(tag);
                    if (w != null) {
                        interestScore += w;
                    }
                    Double sw = shortInterest.get(tag);   // 同源标签：长期命中即看短期层有没有"当下追更"
                    if (sw != null) {
                        shortTermScore += sw;
                    }
                }
            }
        }
        // session 级 attention：候选标签与"最近互动过的内容"重合度 × 那些互动的时效权重之和。
        // 每条 session 互动按 (重合标签数 / 候选标签数) 归一化到 [0,1]，再乘其 recency 权重，
        // 多条约 Recent 互动累加——这正是 target-attention 的最简形态（无 embedding、纯标签重叠）。
        double sessionMatch = 0d;
        List<String> ctags = s.item().tags();
        if (ctags != null && !ctags.isEmpty() && !session.isEmpty()) {
            for (SessionSequenceService.SessionItem it : session) {
                if (it.tags() == null || it.tags().isEmpty()) {
                    continue;
                }
                int hit = 0;
                for (String t : ctags) {
                    if (it.tags().contains(t)) {
                        hit++;
                    }
                }
                if (hit > 0) {
                    sessionMatch += it.recencyWeight() * ((double) hit / ctags.size());
                }
            }
        }
        // 封顶交给模型（RankingProperties#interestScoreCap / #shortTermScoreCap / #sessionScoreCap）。
        // 关键：传<b>原始计数</b>而不是预先算好的比率——置信度平滑只有拿到原始计数才做得了
        // （见 LinearWeightedRankingModel）。短期层/session 关闭时对应分恒为 0。
        // 作者健康分排序系数（抖音式「审核与推荐解耦」接入点）：懒读 tf:mod:account 并按本页缓存，
        // DEMOTE 档(健康分[60,80)）= demoteScale(默认0.5，与 P0-b「推荐降权0.5」对齐)，其余/缺失/读失败=1.0。
        double healthScale = 1.0d;
        if (moderationProperties.isEnableHealthDemotion()) {
            String aid = authorIdOf(s.item());
            if (aid != null) {
                Double cached = authorHealthCache.get(aid);
                if (cached == null) {
                    cached = reviewSignalClient.healthScaleOf(aid);
                    authorHealthCache.put(aid, cached);
                }
                healthScale = cached;
            }
        }
        // 实时特征匹配（抖音式「实时特征流」）：本内容标签与页面级实时画像的亲和度之和。
        // 缺失 / 冷启动 / 读失败 = 0.0（fail-open 不增强也不打压）。
        double realtimeMatch = 0d;
        if (realtimeProfile != null && !realtimeProfile.isEmpty()) {
            List<String> rtags = s.item().tags();
            if (rtags != null) {
                Map<String, Double> aff = realtimeProfile.tagAffinity();
                for (String tag : rtags) {
                    Double a = aff.get(tag);
                    if (a != null) {
                        realtimeMatch += a;
                    }
                }
            }
        }
        return rankingModel.score(new RankingFeatures(
                s.recency(),
                stat.impressions(), stat.playCompletes(), stat.likes(),
                stat.comments(), stat.shares(), stat.dislikes(),
                interestScore,
                shortTermScore,
                sessionMatch,
                matchesNegative(s.item(), negative),
                healthScale,
                realtimeMatch));
    }

    /**
     * 取某作者近期已审内容（关注流 G6 召回用）：读作者索引 ZSET {@code tf:feed:author:{authorId}}，
     * 按入流时刻倒序取最新 cap 条。复用 {@link #parseMember}；任何异常 → 空集（fail-open 不注入社交内容）。
     *
     * @param authorId 作者 userId（来自 tf:follow 社交图）
     * @param cap      最多返回条数
     */
    private List<FeedItemView> authorRecentItems(String authorId, int cap) {
        if (authorId == null || authorId.isEmpty() || cap <= 0) {
            return List.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(AUTHOR_PREFIX + authorId,
                            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, cap);
            if (tuples == null) {
                return List.of();
            }
            List<FeedItemView> out = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                FeedItemView it = parseMember(t == null ? null : t.getValue());
                if (it != null && it.approved()) {
                    out.add(it);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("关注流作者内容读取失败（fail-open 不注入）: authorId={}, {}", authorId, e.getMessage());
            return List.of();
        }
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

    /**
     * 冷启动探索候选（G7）：从 L1/L2（新内容试水池）近 {@code MERGE_BUCKETS} 天桶里，
     * 取入流时刻晚于 {@code threshold} 的新鲜内容，最多 {@code budget} 条。
     * 复用池读取 + parseMember；任何异常 → 空集（fail-open 不注入探索内容）。
     *
     * @param budget    最多返回的候选条数
     * @param threshold  新鲜阈值（入流时刻晚于此即视为冷启动内容）
     * @param maxPool    只取前 maxPool 个池（试水池）
     */
    private List<FeedItemView> collectColdStartCandidates(int budget, Instant threshold, int maxPool) {
        if (budget <= 0) {
            return List.of();
        }
        try {
            List<FeedItemView> out = new ArrayList<>(budget);
            double minScore = threshold.toEpochMilli();
            LocalDate today = LocalDate.now();
            int pools = Math.min(maxPool, MAX_POOL_LEVELS);
            for (int pool = 1; pool <= pools; pool++) {
                for (int d = 0; d < MERGE_BUCKETS; d++) {
                    String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
                    String key = TL_PREFIX + pool + ":" + date;
                    Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                            .reverseRangeByScoreWithScores(key, minScore, Double.POSITIVE_INFINITY, 0, budget * 4);
                    if (tuples == null) {
                        continue;
                    }
                    for (ZSetOperations.TypedTuple<String> t : tuples) {
                        FeedItemView it = parseMember(t == null ? null : t.getValue());
                        if (it != null && it.approved()) {
                            out.add(it);
                        }
                        if (out.size() >= budget) {
                            break;
                        }
                    }
                    if (out.size() >= budget) {
                        break;
                    }
                }
                if (out.size() >= budget) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("冷启动探索候选收集失败（fail-open 不注入）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按内容身份还原完整条目（热点召回用）：读反查索引 {@code tf:feed:idx:{timelineKey}}
     * 取它所在桶 + 成员串，反序列化为 {@link FeedItemView}。
     *
     * <p><b>为什么热点榜只存 timelineKey 而不存整个成员串</b>：榜单需要频繁 ZINCRBY，member
     * 越短越好；且同一内容的成员串可能因重复 append（同 score 不同 JSON）而改写，存 id 不会
     * 留下与当前时间线不一致的僵尸成员。代价是召回时多一次 GET——那是 O(1) 点查，
     * 成本远低于在榜单侧维护冗长 JSON 或做一致性补偿。</p>
     *
     * @return 命中的条目；未入公用时间线 / 索引已过期 / JSON 损坏时返回 {@code null}
     *         （该热点不参与本页，静默丢弃，不影响其余召回路径）
     */
    private FeedItemView memberOf(String timelineKey) {
        if (timelineKey == null) {
            return null;
        }
        try {
            String location = redisTemplate.opsForValue().get(IDX_PREFIX + timelineKey);
            if (location == null) {
                return null;
            }
            int sep = location.indexOf(IDX_SEP);
            if (sep < 0) {
                return null;
            }
            return parseMember(location.substring(sep + IDX_SEP.length()));
        } catch (Exception e) {
            log.warn("热点召回还原条目失败（跳过该条）: timelineKey={}, {}", timelineKey, e.getMessage());
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
            String bucketKey = location.substring(0, sep);
            String member = location.substring(sep + IDX_SEP.length());
            redisTemplate.opsForZSet().remove(bucketKey, member);
            // 同步摘除关注流作者索引（G6）：同成员串，避免已下架内容仍出现在关注流
            String aid = authorIdFromKey(timelineKey);
            if (aid != null) {
                redisTemplate.opsForZSet().remove(AUTHOR_PREFIX + aid, member);
            }
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
