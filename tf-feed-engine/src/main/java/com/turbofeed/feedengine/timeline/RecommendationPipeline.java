package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.feedengine.contract.ItemViewCodec;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.AUTHOR_PREFIX;
import static com.turbofeed.feedengine.timeline.TimelineSupport.IDX_PREFIX;
import static com.turbofeed.feedengine.timeline.TimelineSupport.IDX_SEP;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MAX_POOL_LEVELS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MERGE_BUCKETS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.TL_PREFIX;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdFromKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
// 漏斗编排所需的共享常量与纯函数（与写路径共用一份真值，见 TimelineSupport）
import static com.turbofeed.feedengine.timeline.TimelineSupport.FOLLOW_PER_AUTHOR;
import static com.turbofeed.feedengine.timeline.TimelineSupport.FOLLOW_SCAN_LIMIT;
import static com.turbofeed.feedengine.timeline.TimelineSupport.RECALL_OVERFETCH;
import static com.turbofeed.feedengine.timeline.TimelineSupport.collectModerationKeys;
import static com.turbofeed.feedengine.timeline.TimelineSupport.isModerationBlocked;
import static com.turbofeed.feedengine.timeline.TimelineSupport.matchesNegative;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

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

/**
 * 推荐漏斗编排（抖音式「召回 → 粗排 → 精排 → 重排 → 冷启动混排」），A7。
 *
 * <p><b>为什么析出</b>：原先整条漏斗内联在 {@code FeedTimelineStore#readPage} 一个方法里
 * （该类 1374 行），读模型与推荐策略两种职责耦合在同一处——改一个召回配比要动时间线类，
 * 加一层排序要在 400 行的方法里找插入点。本类把<b>推荐策略</b>整体承接过来，
 * {@code FeedTimelineStore} 回归纯读模型职责（多池 ZSET 读写 + 反查索引 + 游标 + 旁路缓存）。</p>
 *
 * <p><b>职责边界</b>：本类<b>不读时间线</b>——候选由 store 按池取好后以
 * {@code byPool} 传入；本类只负责"怎么把这些候选组织成一页"。反过来，store 也不知道
 * 召回配比、粗排阈值、重排策略，这些都属于本类。双向都不越界，改策略不必动读写模型。</p>
 *
 * <p><b>本次是纯搬迁</b>：方法体与 {@code FeedTimelineStore} 时期逐行一致（含注释与顺序），
 * 行为零变更；结构就位后再各自演进。由 {@code FeedTimelineStoreReadPagePreRankTest} 与
 * 全量单测守护搬运未改变行为。</p>
 *
 * <p>fail-open 贯穿全程：任一路召回 / 粗排 / 重排 / 冷启动失败都退化为"不生效"，
 * 绝不因某一层异常让推荐页开天窗。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationPipeline {

    /**
     * Redis 客户端：本类只在两处直读——热点召回经反查索引还原条目
     * （{@code tf:feed:idx:*}，O(1) 点查）、关注流取作者近期内容 / 冷启动试水池取候选
     * （作者索引与池 ZSET）。<b>时间线本体不由本类读</b>，那是 store 的职责。
     */
    private final StringRedisTemplate redisTemplate;
    /** 成员串反序列化（时间线 ZSET member 是 denormalized {@link FeedItemView} JSON）。 */
    private final ObjectMapper objectMapper;
    /** 参与排序的兴趣标签取 TopN（控制 HGETALL 后排序成本，且避免长尾标签噪声）。 */
    private static final int INTEREST_TOP_N = 30;
    /** 参与排序的 session 序列取最近 N 条（窗口大小；与画像 TopN 解耦）。 */
    private static final int SESSION_TOP_N = 20;
    private final PostStatService postStatService;
    /** 热点榜 L1 本地缓存（P2-6 第二消费者，复用 tf-hotspot LocalCacheWithBroadcast）。 */
    private final HotTimelineKeyCache hotTimelineKeyCache;
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
     * 逐池对候选用廉价特征打分、按分降序<b>重排池内顺序</b>并裁掉尾部，让「兴趣匹配 + 新鲜度」
     * 而不是单纯「入流时刻」决定谁能进这一页。默认启用（{@code preRankKeepRatio=0.3}），
     * 候选量低于 {@code preRankMinCandidates} 的池自动跳过（小数据零变化）。
     */
    private final PreRankFilter preRankFilter;

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

    /** 上下文感知重排服务（G8）：贪心 listwise 重排，强化作者去重与标签多样性，fail-open 回退原序。 */
    private final DiversityRerankService diversityRerankService;
    /** 冷启动探索服务（G7：EE 探索利用）——给新内容 / 低曝光内容固定曝光配额，避免饿死。 */
    private final ColdStartService coldStartService;
    /** 关注关系读取（G6：抖音式「关注流 / 社交分发」推荐消费侧，读 tf:follow:{userId} 社交图）。 */
    private final FollowService followService;
    /** 生态调控层（G9：抖音式「生态调控」整页全局占比配额，与 G8 滑窗重排互补）。 */
    private final EcosystemRegulationService ecosystemRegulationService;

    /**
     * 把一批池候选编排成推荐流的一页（漏斗全链路）。
     *
     * <p>编排顺序（抖音式漏斗，自左向右逐层收窄）：
     * <b>多池候选 → 粗排(G1，逐池重排+截断) → 多路召回(热点/兴趣/向量/关注) →
     * 精排(RankingModel) → 多样性重排(G8) → 冷启动混排(G7) → 生态调控(G9)</b>。</p>
     *
     * @param userId    当前用户（{@code null} 为匿名，走冷启动排序）
     * @param page      页序号（从 0 起）
     * @param limit     单页条数（已由调用方归一，&gt; 0）
     * @param byPool    各流量池候选（按池序 1..{@code MAX_POOL_LEVELS}，含 ZSET score 作入流时刻）
     * @return 本页内容；无内容时为空列表（不是 {@code null}）
     */
    public List<FeedItemView> recommend(String userId, int page, int limit,
                                        Map<Integer, List<ZSetOperations.TypedTuple<String>>> byPool) {

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
        // 关注流作者集合（G6 跨页去重前置）：提前一次性取出，作为「除关注位外所有召回通道」的排除集，
        // 保证关注作者的内容只在首屏关注位曝光一次，不会在深页热点/兴趣/向量/流量池/冷启动位重复刷屏
        // （用户诉求的「整页去重」跨页部分）。关注关系缺失/无关注 → 空集，其余通道行为完全不变。
        Set<String> followeeAuthors = (userId != null && followService.hasFollows(userId))
                ? new HashSet<>(followService.followedAuthors(userId, FOLLOW_SCAN_LIMIT))
                : Set.of();

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
            for (String tk : hotTimelineKeyCache.getHotKeys(hotSlots,
                    () -> postStatService.rawHotTimelineKeys(HotTimelineKeyCache.CAP))) {
                FeedItemView hot = memberOf(tk);
                // 关注作者内容只在首屏关注位曝光，热点通道不再重复刷（跨页去重）。
                if (hot != null && !followeeAuthors.contains(authorIdOf(hot)) && hotKeys.add(tk)) {
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
                if (tk == null || !interestKeys.add(tk) || hotKeys.contains(tk)
                        || followeeAuthors.contains(authorIdFromKey(tk))) {
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
                        || vectorKeys.contains(it.timelineKey())
                        || followeeAuthors.contains(authorIdOf(it))) {
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
        //
        // <b>逐池独立</b>：对每个流量池的候选单独用廉价特征（兴趣/短期/session/实时 + 新鲜度，
        // 不查 DB 统计、不查健康分）打分，按分降序<b>重排池内顺序</b>并裁掉尾部——
        // 于是「谁能进这一页」由粗排分决定，而不再只看入流时刻：池里兴趣更匹配的内容
        // 能顶掉"只是更早入流"的内容。
        //
        // <b>为什么不能跨池统一取 Top-N</b>：下面的分页是「按池权重切片」（allocateSlots），
        // 全局 Top-N（limit×ratio，几十条）与各池当页切片（个位数条）几乎不相交，
        // 开启后流量池对单页的贡献趋近于 0——单页条数塌缩（2026-10-24 修正的缺陷）。
        // 逐池保留 + keep 下限（limit × preRankMinPages 页）保证每池都有足够候选填满自己的切片。
        //
        // 保留条数 = max(limit × preRankMinPages, ceil(池候选量 × ratio))，再受池大小约束；
        // 池候选量 < preRankMinCandidates 时跳过（小数据零变化）。
        // fail-open：preRank 返回空（禁用 / 无候选）即保持原顺序，绝不丢页。
        // ==================================================================
        if (preRankFilter.isEnabled()) {
            PreRankContext preRankCtx = new PreRankContext(interest, shortInterest, session, realtimeProfile);
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                List<ScoredItem> sl = scored.get(pool);
                if (sl == null || sl.isEmpty() || !preRankFilter.isEnabledFor(sl.size())) {
                    continue;
                }
                List<PreRankCandidate> cands = new ArrayList<>(sl.size());
                for (ScoredItem s : sl) {
                    cands.add(new PreRankCandidate(s.item(), s.recency()));
                }
                int keep = Math.min(preRankFilter.keepCount(limit, sl.size()), sl.size());
                List<String> order = preRankFilter.preRank(cands, preRankCtx, keep);
                if (order.isEmpty()) {
                    continue; // fail-open：不裁剪
                }
                Map<String, Integer> rank = new HashMap<>(Math.max(order.size() * 2, 16));
                for (int i = 0; i < order.size(); i++) {
                    rank.put(order.get(i), i);
                }
                List<ScoredItem> reordered = new ArrayList<>(order.size());
                for (ScoredItem s : sl) {
                    if (rank.containsKey(s.item().timelineKey())) {
                        reordered.add(s);
                    }
                }
                reordered.sort(Comparator.comparingInt(
                        s -> rank.getOrDefault(s.item().timelineKey(), Integer.MAX_VALUE)));
                scored.put(pool, reordered);
                // parsed 与 scored 一一对应（见上方解析），同步压缩：槽位分配
                // （allocateSlots 据此判断池非空）与向量召回候选都基于压缩后的集合。
                List<FeedItemView> pl = new ArrayList<>(reordered.size());
                for (ScoredItem s : reordered) {
                    pl.add(s.item());
                }
                parsed.put(pool, pl);
            }
        }

        // ==================================================================
        // 向量召回（抖音式多路召回的第四路）：用户向量 × 内容向量余弦相似度 TopN。
        // 与兴趣召回互补——后者是"显式同标签"，本路是"语义相近但未必同标签"的隐式兴趣。
        // 候选来自流量池全量（排除已占热点/兴趣槽位者），过量取后由通道内部按相似度截断。
        // ==================================================================
        // 向量召回门控：userId 非空 + 配比>0 即开。不再要求兴趣画像非空——
        // model 模式下用户向量来自 KV/共现训练（未必走 InterestService），无画像时通道内部 fail-open 返回空，
        // 不影响其余召回路。hash 模式无画像时 likewise 返回空，行为不变（零回归）。
        int vectorSlots = (userId != null && vectorRecallRatio > 0d)
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
                if (tk == null || !vectorKeys.add(tk) || hotKeys.contains(tk) || interestKeys.contains(tk)
                        || followeeAuthors.contains(authorIdOf(it))) {
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
        // 关注流召回仅在第 0 页注入（page==0）：关注作者是"社交分发强信号"，应置顶于发现流首屏；
        // 若每页都重算并重钉，同一关注作者内容会在翻页时反复刷屏（整页去重前曾导致每页必含、翻页永不枯竭）。
        // 深页中该作者内容仍可由流量池自然浮现，不会丢失曝光。
        int followSlots = (userId != null && page == 0 && followRecallRatio > 0d && !followeeAuthors.isEmpty())
                ? (int) Math.floor(limit * Math.min(followRecallRatio, 1.0d))
                : 0;
        List<ScoredItem> followPart = new ArrayList<>();
        Set<String> followKeys = new LinkedHashSet<>();
        if (followSlots > 0) {
            int budget = followSlots * RECALL_OVERFETCH + 4;
            for (String aid : followeeAuthors) {
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
        // 整页级去重（G6）：抽取到 TimelineDedup，按 timelineKey 记录已入流内容，避免关注/兴趣/向量/热点
        // 与 流量池/冷启动 命中同一内容时在同一页内重复出现（如关注作者内容同时落入关注位与流量池位）；
        // G9 全局占比统计亦因此不被重复计数。与 isModerationBlocked 正交（见 TimelineDedup 类注释）。
        TimelineDedup dedup = new TimelineDedup();

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
            // 粗排已在池解析阶段完成「重排 + 截断」（见上方 G1 段），这里不再二次过滤——
            // 之前在此处用跨池 Top-N 集合过滤当页切片，两者作用域不一致导致单页条数塌缩。
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
            if (isModerationBlocked(it, blockedPosts, blockedAuthors) || !dedup.accept(it)) {
                continue;
            }
            result.add(it);
        }
        // 关注流位紧随热门之后（同为强信号位）：热点是"全站探索"、关注是"社交分发"，都先于兴趣/向量/流量池。
        // 单列于召回层、不参与后续打散——再交给重排层会把"关注优先"的语义抹掉。
        for (ScoredItem s : followPart) {
            if (isModerationBlocked(s.item(), blockedPosts, blockedAuthors) || !dedup.accept(s.item())) {
                continue;
            }
            result.add(s.item());
        }
        // 兴趣召回位紧随热点之后：热点是"全站探索"，兴趣是"个性化兑现"，都先于流量池的通用排序。
        // 不参与后续打散——召回层已按多标签轮转保证覆盖，再交给打散层重排会抹掉"兴趣优先"的语义。
        for (ScoredItem s : interestPart) {
            if (isModerationBlocked(s.item(), blockedPosts, blockedAuthors) || !dedup.accept(s.item())) {
                continue;
            }
            result.add(s.item());
        }
        // 向量召回位紧随兴趣之后（同为个性化位）：热点=全站探索、兴趣/向量=个性化兑现，都先于流量池。
        for (ScoredItem s : vectorPart) {
            if (isModerationBlocked(s.item(), blockedPosts, blockedAuthors) || !dedup.accept(s.item())) {
                continue;
            }
            result.add(s.item());
        }
        // 流量池部分（已打散）入流。
        for (ScoredItem s : poolPart) {
            if (isModerationBlocked(s.item(), blockedPosts, blockedAuthors) || !dedup.accept(s.item())) {
                continue;
            }
            result.add(s.item());
        }
        // 冷启动探索池（G7）：把新鲜内容注入发现流末尾，去重 + 过召回过滤，保证新内容有曝光出路。
        // 复用整页 seen 集合，与热/关注/兴趣/向量/池统一去重，避免任何来源命中同一内容时重复出现。
        if (!coldPart.isEmpty()) {
            for (FeedItemView it : coldPart) {
                // 关注作者内容不在冷启动位重复刷（跨页去重）：只在首屏关注位曝光一次。
                if (isModerationBlocked(it, blockedPosts, blockedAuthors) || followeeAuthors.contains(authorIdOf(it))
                        || !dedup.accept(it)) {
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
    /** 池内重排用的"内容 + 入流时刻(score)"持有体（package-private 以便 {@link DiversityRerankService} 复用）。 */
    record ScoredItem(FeedItemView item, double recency) {
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

    /**
     * 解析时间线成员串。
     *
     * <p>委托 {@link ItemViewCodec}：本方法原先是裸 {@code readValue}，属 M3 收口的 4 处
     * 版本协商旁路之一。管道里所有候选都经这里进入，漏一处就等于整条推荐流绕过协商。</p>
     */
    private FeedItemView parseMember(String json) {
        return ItemViewCodec.parse(objectMapper, json);
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
}