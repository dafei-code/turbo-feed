package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.experiment.ExperimentProperties;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedFilterChain;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.LinearPreRankModel;
import com.turbofeed.feedengine.ranking.PreRankFilter;
import com.turbofeed.feedengine.ranking.RankingFeatures;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.feedengine.ranking.RankingProperties;
import com.turbofeed.feedengine.ranking.RealtimeFeatureService;
import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.feedengine.social.FollowService;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推荐漏斗「多路召回」行为守护（P1 重构前置安全网）。
 *
 * <p><b>为什么单独建</b>：{@link FeedTimelineStoreReadPagePreRankTest} 只 guarding 粗排 × 分页，
 * 且把 4 路召回（hot/interest/vector/follow）<b>全部 stub 关闭</b>——召回通道的排序、跨通道去重、
 * {@code cursor==null}/{@code page==0} 门控<b>没有任何行为测试</b>。这正是 P1「17 依赖收为
 * List&lt;RecallChannel&gt;/List&lt;RerankStage&gt;」重构最易踩坑的地方：一旦把内联召回段抽出成组件，
 * 排序/去重/门控语义很容易被悄悄改掉。本文件在重构前先把<b>当前</b>行为钉死，重构后必须仍绿。</p>
 *
 * <p><b>钉住的行为</b>：
 * <ol>
 *   <li>各召回位的最终顺序：hot → follow → interest → vector → pool → coldstart → ecosystem；
 *   <li>跨通道去重：某内容一旦在更靠前的召回位占坑，后面的池/冷启动位不再重复（hotKeys/interestKeys/
 *       vectorKeys/followeeAuthors 排除集）；
 *   <li>门控：所有召回位都要求 {@code cursor == null}；follow 位额外要求 {@code page == 0}。</li>
 * </ol>
 * 纯 Mockito（Redis / DB / 审核信号全 mock），与 {@code FeedTimelineStoreReadPagePreRankTest} 同构。</p>
 *
 * <p><b>key 约定</b>：所有条目 timelineKey 一律用真实 {@code post/{authorId}/{seq}} 形态，
 * 确保 {@link TimelineSupport#authorIdOf} 能解析出非 null 作者（与线上成员串一致）；
 * 否则非 {@code post/} 前缀会让 {@code authorIdOf} 返回 null，而空 {@code followeeAuthors}
 * 是不可变 {@code Set.of()}，其 {@code contains(null)} 会 NPE——这是测试数据问题，非生产 bug。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationPipelineRecallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String TL_PREFIX = "tf:feed:tl:";
    private static final String IDX_PREFIX = "tf:feed:idx:";
    private static final String AUTHOR_PREFIX = "tf:feed:author:";
    private static final String IDX_SEP = "\u0001";
    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private PostStatService postStatService;
    @Mock
    private HotTimelineKeyCache hotTimelineKeyCache;
    @Mock
    private InterestService interestService;
    @Mock
    private SessionSequenceService sessionSequenceService;
    @Mock
    private RankingModel rankingModel;
    @Mock
    private ReviewSignalClient reviewSignalClient;
    @Mock
    private FeedModerationProperties moderationProperties;
    @Mock
    private VectorRecallChannel vectorRecallChannel;
    @Mock
    private RealtimeFeatureService realtimeFeatureService;
    @Mock
    private FollowService followService;
    @Mock
    private FeedFilterChain feedFilterChain;

    private final ZSetOperations<String, String> zSet = mock();

    /** 索引反查桩（hot/interest 经 memberOf 还原条目用）：tk → 成员 JSON。 */
    private final Map<String, String> indexStubs = new LinkedHashMap<>();
    /** 作者 ZSET 桩（follow 召回用）：authorId → 成员 tuple 集合。 */
    private Map<String, Set<ZSetOperations.TypedTuple<String>>> authorStubs = Map.of();
    /** 流量池候选（池解析 + 分页用；与 {@link RecommendationPipeline#recommend} 形参同型 List）。 */
    private Map<Integer, List<ZSetOperations.TypedTuple<String>>> byPool = Map.of();

    /** 造一条帖子条目：timelineKey 即传入的 tk（须为 post/{authorId}/{seq} 形态，与断言对齐）。 */
    private static FeedItemView item(String tk) {
        return new FeedItemView(tk, "m:" + tk, "url", null,
                FeedItemView.STATUS_APPROVED, Instant.now(), "cap", null, List.of());
    }

    private static String write(FeedItemView it) {
        try {
            return MAPPER.writeValueAsString(it);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 反查索引值：{桶key}<SOH>{成员JSON}（与 TimelineSupport.indexValue 对称）。 */
    private static String indexValue(String bucketKey, FeedItemView it) {
        return bucketKey + IDX_SEP + write(it);
    }

    /** 构造 pipeline：召回配比经 @Value 字段注入（纯 new 不注入，须反射设置）。 */
    private RecommendationPipeline pipeline(double hot, double interest, double vector, double follow) {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSet);
        RecallSupport recallSupport = new RecallSupport(redisTemplate, MAPPER, postStatService,
                rankingModel, reviewSignalClient, moderationProperties, feedFilterChain);
        List<RecallChannel> recallChannels = List.of(
                new HotRecallChannel(hotTimelineKeyCache, postStatService),
                new InterestRecallChannel(interestService, recallSupport),
                new VectorFeedRecallChannel(vectorRecallChannel, recallSupport),
                new FollowRecallChannel(recallSupport));
        RerankStage diversityStage = mock(RerankStage.class);
        lenient().when(diversityStage.phase()).thenReturn(RerankPhase.POOL);
        lenient().when(diversityStage.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        RerankStage coldStage = mock(RerankStage.class);
        lenient().when(coldStage.phase()).thenReturn(RerankPhase.COLD);
        lenient().when(coldStage.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        RerankStage ecosystemStage = mock(RerankStage.class);
        lenient().when(ecosystemStage.phase()).thenReturn(RerankPhase.FINAL);
        lenient().when(ecosystemStage.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        List<RerankStage> rerankStages = List.of(diversityStage, coldStage, ecosystemStage);
        RecommendationPipeline p = new RecommendationPipeline(followService, sessionSequenceService,
                reviewSignalClient, moderationProperties, realtimeFeatureService,
                new PreRankFilter(new LinearPreRankModel(new RankingProperties()), new RankingProperties()),
                recallSupport, recallChannels, rerankStages, interestService,
                new ExperimentProperties()); // 实验默认关：走默认配比，守护默认路径不变
        ReflectionTestUtils.setField(p, "poolWeightsCsv", "0.34,0.33,0.33");
        ReflectionTestUtils.setField(p, "hotRecallRatio", hot);
        ReflectionTestUtils.setField(p, "interestRecallRatio", interest);
        ReflectionTestUtils.setField(p, "vectorRecallRatio", vector);
        ReflectionTestUtils.setField(p, "followRecallRatio", follow);
        // 关闭过滤链 → 走内联 passFilters（blocked 集空 → 仅整页去重）；避免再 stub feedFilterChain
        ReflectionTestUtils.setField(p, "filterChainEnabled", false);
        return p;
    }

    /** 公共依赖置零/关闭，让结果只由「池候选 + 召回」决定（精排恒 0 分 → 顺序稳定可断言）。 */
    private void stubCommon() {
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of());
        lenient().when(interestService.hasInterest(anyString())).thenReturn(false);
        lenient().when(interestService.weightedTags(anyString(), anyInt())).thenReturn(Map.of());
        lenient().when(interestService.shortTermTags(anyString(), anyInt())).thenReturn(Map.of());
        lenient().when(interestService.negativeTags(anyString())).thenReturn(Set.of());
        lenient().when(interestService.recallTimelineKeys(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(sessionSequenceService.recent(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(realtimeFeatureService.profileOf(anyString())).thenReturn(null);
        lenient().when(followService.hasFollows(anyString())).thenReturn(false);
        lenient().when(followService.followedAuthors(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(postStatService.snapshot(anyString()))
                .thenReturn(new PostStatService.PostStat(0, 0, 0, 0, 0, 0, 0));
        lenient().when(rankingModel.score(any(RankingFeatures.class))).thenReturn(0d);
        lenient().when(moderationProperties.isEnableRecallFilter()).thenReturn(false);
        lenient().when(moderationProperties.isEnableHealthDemotion()).thenReturn(false);
        // 索引反查 + 作者 ZSET 统一走桩表
        lenient().when(valueOps.get(anyString())).thenAnswer(inv -> indexStubs.get(inv.getArgument(0)));
        lenient().when(zSet.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    if (key.startsWith(AUTHOR_PREFIX)) {
                        return authorStubs.getOrDefault(key.substring(AUTHOR_PREFIX.length()), Set.of());
                    }
                    // 池候选由 recommend 的 byPool 形参直传，不经 reverseRangeByScoreWithScores；
                    // 仅冷启动候选收集会走 TL_PREFIX 桶读，本测试冷启动预算为 0（不触发），统一返回空集。
                    if (key.startsWith(TL_PREFIX)) {
                        return Set.of();
                    }
                    return Set.of();
                });
    }

    /** 造流量池候选：每池 count 条，timelineKey = post/p{p}/i{j}（authorId = p{p}，与召回 key 不重叠）。 */
    private void stubPools(int perPool) {
        Map<Integer, List<ZSetOperations.TypedTuple<String>>> m = new LinkedHashMap<>();
        for (int p = 1; p <= 3; p++) {
            List<ZSetOperations.TypedTuple<String>> set = new ArrayList<>();
            for (int i = 0; i < perPool; i++) {
                String tk = "post/p" + p + "/i" + i;
                FeedItemView it = item(tk);
                set.add(new DefaultTypedTuple<>(write(it), 1_700_000_000_000d - (double) i));
            }
            m.put(p, set);
        }
        byPool = m;
    }

    private static List<String> keysOf(List<FeedItemView> items) {
        List<String> keys = new ArrayList<>(items.size());
        for (FeedItemView it : items) {
            keys.add(it.timelineKey());
        }
        return keys;
    }

    /** 把某个 tk 的反查索引桩好（hot/interest 召回经 memberOf 还原）。 */
    private void stubIndex(String tk, FeedItemView it) {
        indexStubs.put(IDX_PREFIX + tk, indexValue(TL_PREFIX + "1:" + TODAY, it));
    }

    /** 造某作者的近期内容 ZSET 桩（follow 召回经 authorRecentItems 还原）。 */
    private static Set<ZSetOperations.TypedTuple<String>> authorTuples(String authorId, String... tks) {
        Set<ZSetOperations.TypedTuple<String>> set = new LinkedHashSet<>();
        double score = 1_700_000_000_000d;
        for (String tk : tks) {
            set.add(new DefaultTypedTuple<>(write(item(tk)), score));
            score -= 1_000d;
        }
        return set;
    }

    // ===== 用例 =====

    @Test
    void hotRecall_pinnedFirst_andDedupedFromPool() {
        stubCommon();
        stubPools(30);
        stubIndex("post/hot/1", item("post/hot/1"));
        stubIndex("post/hot/2", item("post/hot/2"));
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of("post/hot/1", "post/hot/2"));

        List<String> keys = keysOf(pipeline(0.2d, 0d, 0d, 0d).recommend("u1", 0, 20, byPool, null));

        // 热点位钉最前，且顺序 = getHotKeys 顺序（scoreOf 恒 0 → 稳定）
        assertEquals(List.of("post/hot/1", "post/hot/2"), keys.subList(0, 2), "热点召回须钉在最前，顺序同榜单");
        // 池部分不得重复出现热点内容（hotKeys 排除集生效）
        assertFalse(keys.subList(2, keys.size()).contains("post/hot/1"), "热点内容不得再占流量池槽位");
        assertFalse(keys.subList(2, keys.size()).contains("post/hot/2"), "热点内容不得再占流量池槽位");
    }

    @Test
    void followRecall_presentAtPageZero_andAuthorExcludedFromPool() {
        stubCommon();
        stubPools(30);
        // 关注作者近期内容（member 经作者 ZSET 还原）
        String authorA = "authorA";
        authorStubs = Map.of(authorA, authorTuples(authorA, "post/authorA/f1", "post/authorA/f2"));
        lenient().when(followService.hasFollows(anyString())).thenReturn(true);
        lenient().when(followService.followedAuthors(anyString(), anyInt())).thenReturn(List.of(authorA));
        // 池内放一条同作者内容，验证被 followeeAuthors 排除（不占池/冷启动位）
        byPool.get(1).add(new DefaultTypedTuple<>(write(item("post/authorA/pool1")), 1_700_000_000_500d));

        List<String> keys = keysOf(pipeline(0d, 0d, 0d, 0.2d).recommend("u1", 0, 20, byPool, null));

        assertTrue(keys.contains("post/authorA/f1"), "page=0 时关注位须注入");
        assertTrue(keys.contains("post/authorA/f2"), "page=0 时关注位须注入");
        assertFalse(keys.contains("post/authorA/pool1"),
                "关注作者内容不得再占流量池槽位（跨页去重：followeeAuthors 排除集）");
    }

    @Test
    void followRecall_absentWhenPageNotZero() {
        stubCommon();
        stubPools(30);
        String authorA = "authorA";
        authorStubs = Map.of(authorA, authorTuples(authorA, "post/authorA/f1"));
        lenient().when(followService.hasFollows(anyString())).thenReturn(true);
        lenient().when(followService.followedAuthors(anyString(), anyInt())).thenReturn(List.of(authorA));

        // page=1（cursor 仍为空）→ 关注位门控 page==0 不生效
        List<String> keys = keysOf(pipeline(0d, 0d, 0d, 0.2d).recommend("u1", 1, 20, byPool, null));

        assertFalse(keys.contains("post/authorA/f1"), "page≠0 时关注流召回必须关闭（只在首屏注入）");
    }

    @Test
    void interestRecall_present_andDedupedFromPool() {
        stubCommon();
        stubPools(30);
        stubIndex("post/int/1", item("post/int/1"));
        stubIndex("post/int/2", item("post/int/2"));
        lenient().when(interestService.hasInterest(anyString())).thenReturn(true);
        lenient().when(interestService.weightedTags(anyString(), anyInt())).thenReturn(Map.of("x", 1.0d));
        lenient().when(interestService.recallTimelineKeys(anyString(), anyInt())).thenReturn(List.of("post/int/1", "post/int/2"));

        List<String> keys = keysOf(pipeline(0d, 0.2d, 0d, 0d).recommend("u1", 0, 20, byPool, null));

        assertTrue(keys.contains("post/int/1"), "兴趣召回位须注入");
        assertTrue(keys.contains("post/int/2"), "兴趣召回位须注入");
        assertFalse(keys.subList(keys.indexOf("post/int/2") + 1, keys.size()).contains("post/int/1")
                        && keys.stream().filter(k -> k.equals("post/int/1")).count() > 1,
                "兴趣内容不得重复出现（interestKeys 排除集）");
    }

    @Test
    void vectorRecall_present_andDedupedFromPool() {
        stubCommon();
        stubPools(30);
        FeedItemView v1 = item("post/vec/1");
        FeedItemView v2 = item("post/vec/2");
        lenient().when(vectorRecallChannel.recall(eq("u1"), anyList(), anyInt())).thenReturn(List.of(v1, v2));

        List<String> keys = keysOf(pipeline(0d, 0d, 0.2d, 0d).recommend("u1", 0, 20, byPool, null));

        assertTrue(keys.contains("post/vec/1"), "向量召回位须注入");
        assertTrue(keys.contains("post/vec/2"), "向量召回位须注入");
    }

    @Test
    void cursorNotNull_disablesAllRecallChannels() {
        stubCommon();
        stubPools(30);
        // 四路召回数据全部就绪，但 cursor 非空 → 全部门控关闭
        stubIndex("post/hot/1", item("post/hot/1"));
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of("post/hot/1"));
        stubIndex("post/int/1", item("post/int/1"));
        lenient().when(interestService.hasInterest(anyString())).thenReturn(true);
        lenient().when(interestService.weightedTags(anyString(), anyInt())).thenReturn(Map.of("x", 1.0d));
        lenient().when(interestService.recallTimelineKeys(anyString(), anyInt())).thenReturn(List.of("post/int/1"));
        lenient().when(vectorRecallChannel.recall(eq("u1"), anyList(), anyInt())).thenReturn(List.of(item("post/vec/1")));
        String authorA = "authorA";
        authorStubs = Map.of(authorA, authorTuples(authorA, "post/authorA/f1"));
        lenient().when(followService.hasFollows(anyString())).thenReturn(true);
        lenient().when(followService.followedAuthors(anyString(), anyInt())).thenReturn(List.of(authorA));

        List<String> keys = keysOf(pipeline(0.2d, 0.2d, 0.2d, 0.2d)
                .recommend("u1", 0, 20, byPool, FeedCursor.of(1_700_000_000_000d, "post/x/y")));

        assertFalse(keys.contains("post/hot/1"), "cursor≠null 时热点召回必须关闭");
        assertFalse(keys.contains("post/int/1"), "cursor≠null 时兴趣召回必须关闭");
        assertFalse(keys.contains("post/vec/1"), "cursor≠null 时向量召回必须关闭");
        assertFalse(keys.contains("post/authorA/f1"), "cursor≠null 时关注召回必须关闭");
    }

    @Test
    void pageNotZero_disablesFollowOnly_keepsHotInterestVector() {
        stubCommon();
        stubPools(30);
        stubIndex("post/hot/1", item("post/hot/1"));
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of("post/hot/1"));
        stubIndex("post/int/1", item("post/int/1"));
        lenient().when(interestService.hasInterest(anyString())).thenReturn(true);
        lenient().when(interestService.weightedTags(anyString(), anyInt())).thenReturn(Map.of("x", 1.0d));
        lenient().when(interestService.recallTimelineKeys(anyString(), anyInt())).thenReturn(List.of("post/int/1"));
        lenient().when(vectorRecallChannel.recall(eq("u1"), anyList(), anyInt())).thenReturn(List.of(item("post/vec/1")));
        String authorA = "authorA";
        authorStubs = Map.of(authorA, authorTuples(authorA, "post/authorA/f1"));
        lenient().when(followService.hasFollows(anyString())).thenReturn(true);
        lenient().when(followService.followedAuthors(anyString(), anyInt())).thenReturn(List.of(authorA));

        // page=1，cursor 空 → 仅 follow 关闭，其余召回仍生效
        List<String> keys = keysOf(pipeline(0.2d, 0.2d, 0.2d, 0.2d).recommend("u1", 1, 20, byPool, null));

        assertTrue(keys.contains("post/hot/1"), "page≠0 时热点召回仍须生效（仅 follow 受 page==0 门控）");
        assertTrue(keys.contains("post/int/1"), "page≠0 时兴趣召回仍须生效");
        assertTrue(keys.contains("post/vec/1"), "page≠0 时向量召回仍须生效");
        assertFalse(keys.contains("post/authorA/f1"), "page≠0 时关注召回必须关闭");
    }
}
