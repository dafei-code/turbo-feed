package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedFilterChain;
import com.turbofeed.feedengine.ranking.FeedFilterContext;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.PreRankCandidate;
import com.turbofeed.feedengine.ranking.PreRankContext;
import com.turbofeed.feedengine.ranking.PreRankFilter;
import com.turbofeed.feedengine.ranking.RealtimeFeatureService;
import com.turbofeed.feedengine.ranking.UserRealtimeProfile;
import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.feedengine.social.FollowService;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.FOLLOW_SCAN_LIMIT;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MAX_POOL_LEVELS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.collectModerationKeys;

/**
 * 公域发现流时间线读模型（写时物化）。
 *
 * <p><b>从 tf-gateway 迁入</b>：本类原属网关 {@code com.turbofeed.gateway.service.feed}，
 * 是"Feed 引擎推模式"在网关进程内的雏形。服务拆分后，Feed 时间线的物化与读取整体归位到本引擎——
 * 网关只保留 HTTP 接入与审核状态机，通过内部接口投递变更，不再直接持有任何 Feed 读模型。</p>
 *
 * <p><b>解决的问题</b>：原 {@code MediaJdbcRepository#listApprovedGlobal} 不带分片键，
 * ShardingSphere 广播到全部分片归并——百亿行下每翻一页都全表扫，直接拖垮所有分片。
 * 本类把可见性从"读时扫描"变为"写时产生"：审核通过即写入 Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}，读路径合并近 {@link #MAX_POOL_LEVELS} 天分桶按 score
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
 * 推荐漏斗编排（抖音式「召回 → 粗排 → 精排 → 重排 → 冷启动混排」），A7 + P1。
 *
 * <p><b>P1 组件化</b>：原内联的 4 路召回（hot/interest/vector/follow）抽为 {@link RecallChannel}
 * （按 {@link RecallPhase} 自动收集），3 层重排（多样性 G8 / 冷启动 G7 / 生态调控 G9）抽为
 * {@link RerankStage}（按 {@link RerankPhase} 自动收集），共享的评分 / 还原 / 审核过滤抽到
 * {@link RecallSupport}。本类只负责"编排顺序 + 门控 + 跨通道去重 + 池切片分配"，
 * 行为与原内联实现逐行一致（守护测试 {@code RecommendationPipelineRecallTest} 锁定）。</p>
 *
 * <p><b>职责边界</b>：本类<b>不读时间线</b>——候选由 store 按池取好后以
 * {@code byPool} 传入；本类只负责"怎么把这些候选组织成一页"。</p>
 *
 * <p>fail-open 贯穿全程：任一路召回 / 粗排 / 重排 / 冷启动失败都退化为"不生效"，
 * 绝不因某一层异常让推荐页开天窗。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationPipeline {

    /** 参与排序的兴趣标签取 TopN（控制 HGETALL 后排序成本，且避免长尾标签噪声）。 */
    private static final int INTEREST_TOP_N = 30;
    /** 参与排序的 session 序列取最近 N 条（窗口大小；与画像 TopN 解耦）。 */
    private static final int SESSION_TOP_N = 20;
    /** 关注关系读取（G6：抖音式「关注流 / 社交分发」推荐消费侧，读 tf:follow:{userId} 社交图）。 */
    private final FollowService followService;
    /** 会话级行为序列（最近互动），用于精排的"跟手"信号（见 {@link SessionSequenceService}）。 */
    private final SessionSequenceService sessionSequenceService;
    /**
     * 审核信号读取客户端（抖音式「审核与推荐解耦」：推荐侧直读 {@code tf:mod:} KV）。
     * 召回层用它过滤 INTERCEPT/MONITOR 内容与低健康分作者；排序层经 {@link RecallSupport#scoreOf}
     * 懒读作者健康分降权。
     */
    private final ReviewSignalClient reviewSignalClient;
    /** 推荐侧消费审核信号的开关与阈值（namespace / 召回过滤 / 降权系数 / 剔除阈值）。 */
    private final FeedModerationProperties moderationProperties;
    /** 实时特征聚合（抖音式「实时特征流」）：每页算一次，喂给排序模型。 */
    private final RealtimeFeatureService realtimeFeatureService;
    /**
     * 粗排截断过滤器（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。
     * 逐池对候选用廉价特征打分、按分降序<b>重排池内顺序</b>并裁掉尾部，让「兴趣匹配 + 新鲜度」
     * 而不是单纯「入流时刻」决定谁能进这一页。默认启用（{@code preRankKeepRatio=0.3}），
     * 候选量低于 {@code preRankMinCandidates} 的池自动跳过（小数据零变化）。
     */
    private final PreRankFilter preRankFilter;
    /** 召回 / 重排共享助手（scoreOf / memberOf / 审核过滤等抽到此，供 RecallChannel / RerankStage 复用）。 */
    private final RecallSupport recallSupport;
    /** 召回通道（抖音式多路召回：热点/兴趣/向量/关注），按 phase 自动收集，编排顺序由 recommend 控制。 */
    private final List<RecallChannel> recallChannels;
    /** 重排阶段（多样性重排 G8 / 冷启动 G7 / 生态调控 G9），按 phase 自动收集。 */
    private final List<RerankStage> rerankStages;
    /** 兴趣画像服务（长期/短期标签 → 权重，负反馈，召回索引）。 */
    private final InterestService interestService;

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
     * 推荐过滤规则链开关（默认开启新链）。关闭时 RecallSupport.passFilters 回退到与旧内联完全等价的判定，
     * 作为一键回滚保险——行为零变更。
     */
    @Value("${turbofeed.feed.filter-chain-enabled:true}")
    private boolean filterChainEnabled;

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
                                        Map<Integer, List<ZSetOperations.TypedTuple<String>>> byPool,
                                        FeedCursor cursor) {

        // 一次性取用户兴趣画像（TopN 正向标签→权重）；无画像/匿名→空 Map，走冷启动排序。
        Map<String, Double> interest = (userId == null || !interestService.hasInterest(userId))
                ? Map.of() : interestService.weightedTags(userId, INTEREST_TOP_N);
        Map<String, Double> shortInterest = (userId == null || !interestService.hasInterest(userId))
                ? Map.of() : interestService.shortTermTags(userId, INTEREST_TOP_N);
        // session 级最近互动序列（一次取出，scoreOf 内逐候选复用，避免每个候选各查一次 Redis）。
        List<SessionSequenceService.SessionItem> session = (userId == null)
                ? List.of() : sessionSequenceService.recent(userId, SESSION_TOP_N);
        // 负向标签（抖音式「不感兴趣 → 对该用户打压同标签内容」）。匿名 / 无负反馈为空集。
        Set<String> negative = interestService.negativeTags(userId);
        // 实时特征画像（抖音式「实时特征流」）：每页只算一次，喂排序模型（见 RealtimeFeatureService）。
        UserRealtimeProfile realtimeProfile = realtimeFeatureService.profileOf(userId);
        // 关注流作者集合（G6 跨页去重前置）：提前一次性取出，作为「除关注位外所有召回通道」的排除集，
        // 保证关注作者的内容只在首屏关注位曝光一次，不会在深页热点/兴趣/向量/流量池/冷启动位重复刷屏。
        Set<String> followeeAuthors = (userId != null && followService.hasFollows(userId))
                ? new HashSet<>(followService.followedAuthors(userId, FOLLOW_SCAN_LIMIT))
                : Set.of();

        double[] weights = parsePoolWeights();

        RecallContext ctx = new RecallContext(userId, page, limit, cursor, interest, shortInterest,
                session, negative, realtimeProfile, followeeAuthors, new LinkedHashMap<>(), recallSupport);

        // ==================================================================
        // PRE_POOL 召回：热点 / 兴趣（不依赖池解析结果）。热点通道写 hotKeys，兴趣通道读 hotKeys 并写 interestKeys。
        // ==================================================================
        Map<RecallType, List<ScoredItem>> preParts = runRecall(ctx, RecallPhase.PRE_POOL);
        List<ScoredItem> hotPart = preParts.getOrDefault(RecallType.HOT, List.of());
        List<ScoredItem> interestPart = preParts.getOrDefault(RecallType.INTEREST, List.of());
        List<FeedItemView> hotItems = new ArrayList<>(hotPart.size());
        for (ScoredItem s : hotPart) {
            hotItems.add(s.item());   // 热点位后续单独钉最前，不进 ScoredItem 流
        }

        // ==================================================================
        // 流量池解析：按入流时刻(score)归并各池候选；用 hotKeys/interestKeys/followeeAuthors 做跨通道去重。
        // ==================================================================
        Map<Integer, List<FeedItemView>> parsed = new LinkedHashMap<>();
        Map<Integer, List<ScoredItem>> scored = new LinkedHashMap<>();
        for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
            List<FeedItemView> l = new ArrayList<>();
            List<ScoredItem> sl = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> t : byPool.get(pool)) {
                FeedItemView it = recallSupport.parseMember(t == null ? null : t.getValue());
                if (it == null) {
                    continue;
                }
                // keyset 游标：跳过游标所指项及之后（翻页严格单调、跨页不重复）
                if (cursor != null && cursor.excludes(
                        t != null && t.getScore() != null ? t.getScore() : 0d, it.timelineKey())) {
                    continue;
                }
                // 同页去重：已在热点槽位 / 兴趣召回位露出的内容不再占流量池槽位；关注作者内容也不在池位重复刷。
                if (ctx.hotKeys.contains(it.timelineKey()) || ctx.interestKeys.contains(it.timelineKey())
                        || ctx.vectorKeys.contains(it.timelineKey())
                        || ctx.followeeAuthors.contains(authorIdOf(it))) {
                    continue;
                }
                l.add(it);
                double recency = t != null && t.getScore() != null ? t.getScore() : 0d;
                sl.add(new ScoredItem(it, recency));
            }
            parsed.put(pool, l);
            scored.put(pool, sl);
        }
        ctx.parsed = parsed;   // 供向量召回取全量候选（POST_POOL，池解析+粗排之后）

        // ==================================================================
        // 粗排截断（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。逐池独立、按池权重切片，
        // 保留条数 = max(limit × preRankMinPages, ceil(池候选量 × ratio))，再受池大小约束；
        // 池候选量 < preRankMinCandidates 时跳过（小数据零变化）。fail-open：返回空即保持原顺序。
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
                // parsed 与 scored 一一对应（见上方解析），同步压缩：槽位分配与向量召回候选都基于压缩后的集合。
                List<FeedItemView> pl = new ArrayList<>(reordered.size());
                for (ScoredItem s : reordered) {
                    pl.add(s.item());
                }
                parsed.put(pool, pl);
            }
        }

        // ==================================================================
        // POST_POOL 召回：向量 / 关注（向量需池全量候选；关注需 followeeAuthors 已就绪）。
        // 向量通道写 vectorKeys（关注通道读它），关注通道写 followKeys。
        // ==================================================================
        Map<RecallType, List<ScoredItem>> postParts = runRecall(ctx, RecallPhase.POST_POOL);
        List<ScoredItem> vectorPart = postParts.getOrDefault(RecallType.VECTOR, List.of());
        List<ScoredItem> followPart = postParts.getOrDefault(RecallType.FOLLOW, List.of());

        int remainingSlots = Math.max(limit - hotItems.size() - followPart.size() - interestPart.size() - vectorPart.size(), 0);
        int[] alloc = allocateSlots(remainingSlots, weights, parsed);
        long pageNum = Math.max(page, 0);
        List<FeedItemView> result = new ArrayList<>(limit);
        // 整页级去重（G6）：按 timelineKey 记录已入流内容，避免关注/兴趣/向量/热点与流量池/冷启动命中同一内容时
        // 在同一页内重复出现；G9 全局占比统计亦因此不被重复计数。与 isModerationBlocked 正交。
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
            int start = (int) Math.min(per * pageNum, items.size());
            int end = Math.min(start + per, items.size());
            if (start >= end) {
                continue;
            }
            // 池内按"入流时刻 + 完播率加权"重排：完播率高的内容在同类 cohort 里往前排。仅对当页切片重排，
            // 精排统一交给 RankingModel（含作者健康分降权）。恒重排——少一个分支就少一处静默分歧。
            List<ScoredItem> slice = new ArrayList<>(items.subList(start, end));
            slice.sort((a, b) -> Double.compare(scoreOf(b, ctx), scoreOf(a, ctx)));
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
        // P2-PR06：构造过滤链上下文（审核拦截集 + 整页去重），一次 recommend 内共享。
        FeedFilterContext filterCtx = new FeedFilterContext(blockedPosts, blockedAuthors, dedup);
        ctx.filterChainEnabled = filterChainEnabled;
        ctx.filterCtx = filterCtx;

        // 流量池部分：统一走一次上下文感知重排（G8：整页范围强化作者去重 + 标签多样性），再入流。
        List<FeedItemView> poolItems = new ArrayList<>(poolPart.size());
        for (ScoredItem s : poolPart) {
            poolItems.add(s.item());
        }
        poolItems = applyRerank(RerankPhase.POOL, ctx, poolItems);

        // 热点槽位<b>固定钉在最前</b>（先过召回过滤）：它是召回策略刻意给的探索位，不参与后续打散。
        for (FeedItemView it : hotItems) {
            if (!recallSupport.passFilters(it, ctx)) {
                continue;
            }
            result.add(it);
        }
        // 关注流位紧随热门之后（同为强信号位）：热点是"全站探索"、关注是"社交分发"，都先于兴趣/向量/流量池。
        for (ScoredItem s : followPart) {
            if (!recallSupport.passFilters(s.item(), ctx)) {
                continue;
            }
            result.add(s.item());
        }
        // 兴趣召回位紧随热点之后：热点是"全站探索"，兴趣是"个性化兑现"，都先于流量池的通用排序。
        for (ScoredItem s : interestPart) {
            if (!recallSupport.passFilters(s.item(), ctx)) {
                continue;
            }
            result.add(s.item());
        }
        // 向量召回位紧随兴趣之后（同为个性化位）：热点=全站探索、兴趣/向量=个性化兑现，都先于流量池。
        for (ScoredItem s : vectorPart) {
            if (!recallSupport.passFilters(s.item(), ctx)) {
                continue;
            }
            result.add(s.item());
        }
        // 流量池部分（已打散）入流。
        for (FeedItemView it : poolItems) {
            if (!recallSupport.passFilters(it, ctx)) {
                continue;
            }
            result.add(it);
        }

        // 冷启动探索池（G7）：把新鲜内容注入发现流末尾，去重 + 过召回过滤，保证新内容有曝光出路。
        // 复用整页 seen 集合，与热/关注/兴趣/向量/池统一去重，避免任何来源命中同一内容时重复出现。
        result = applyRerank(RerankPhase.COLD, ctx, result);
        // 生态调控层（G9：抖音式「整页全局占比配额」，与 G8 滑窗重排互补）：对整页内容做全局作者/标签封顶，
        // 防单一作者/话题垄断整页；保序裁低位超额尾部，fail-open 回退原序（整页范围生效，含冷启动注入位）。
        result = applyRerank(RerankPhase.FINAL, ctx, result);
        return result;
    }

    /** 按 phase 运行所有召回通道，返回 type → 产出（各通道只产出自己 type 的一份）。 */
    private Map<RecallType, List<ScoredItem>> runRecall(RecallContext ctx, RecallPhase phase) {
        Map<RecallType, List<ScoredItem>> out = new LinkedHashMap<>();
        for (RecallChannel ch : recallChannels) {
            if (ch.phase() != phase) {
                continue;
            }
            int slots = slotsFor(ch.type(), ctx);
            if (slots <= 0) {
                continue;
            }
            out.put(ch.type(), ch.recall(ctx, slots));
        }
        return out;
    }

    /**
     * 某通道的槽位数（含门控）：配比 &gt; 0 且 cursor==null 才开；interest 额外要求画像非空，
     * vector 额外要求 userId 非空，follow 额外要求 page==0 且有关注作者。floor 后取 min(ratio,1)。
     */
    private int slotsFor(RecallType t, RecallContext ctx) {
        double ratio = switch (t) {
            case HOT -> hotRecallRatio;
            case INTEREST -> interestRecallRatio;
            case VECTOR -> vectorRecallRatio;
            case FOLLOW -> followRecallRatio;
        };
        if (ratio <= 0d || ctx.cursor != null) {
            return 0;
        }
        int slots = (int) Math.floor(ctx.limit * Math.min(ratio, 1.0d));
        if (t == RecallType.INTEREST && (ctx.userId == null || ctx.interest.isEmpty())) {
            return 0;
        }
        if (t == RecallType.VECTOR && ctx.userId == null) {
            return 0;
        }
        if (t == RecallType.FOLLOW && (ctx.userId == null || ctx.page != 0 || ctx.followeeAuthors.isEmpty())) {
            return 0;
        }
        return slots;
    }

    /** 按 phase 依次应用对应的重排阶段（COLD/FINAL 各自只一个阶段）。 */
    private List<FeedItemView> applyRerank(RerankPhase phase, RecallContext ctx, List<FeedItemView> page) {
        List<FeedItemView> result = page;
        for (RerankStage stage : rerankStages) {
            if (stage.phase() == phase) {
                result = stage.rerank(ctx, result);
            }
        }
        return result;
    }

    /** 组装排序特征并交 RankingModel 打分（委托 {@link RecallSupport}，逐候选复用 ctx 内画像/会话/实时特征）。 */
    private double scoreOf(ScoredItem s, RecallContext ctx) {
        return recallSupport.scoreOf(s, ctx);
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
