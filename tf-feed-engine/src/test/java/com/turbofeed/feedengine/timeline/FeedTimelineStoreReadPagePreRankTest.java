package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.feedengine.client.ReviewSignalClient;
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
 * {@link FeedTimelineStore#readPage} × 粗排（G1）的<b>组合</b>单测。
 *
 * <p>为什么单开一个文件：{@link FeedTimelineStoreTest} 只覆盖 append / promote / remove 三条写路径，
 * 而 readPage（最复杂的读路径）长期无单测，粗排又恰好是「过滤器自身正确、与分页组合起来错误」的
 * 典型——{@link PreRankFilterTest} 测不到它。本文件专门盯「粗排开启后单页还能不能填满」。</p>
 *
 * <p><b>它守护的缺陷（2026-10-24）</b>：旧实现跨池统一取 {@code limit × ratio} 条，再用这个
 * 全局 Top-N 集合去过滤各池的当页切片（个位数条），两者几乎不相交 → 流量池对单页的贡献趋近于 0，
 * 单页条数塌缩。修法是改为逐池重排 + 截断。用例 {@link #readPage_preRankEnabled_pageNotCollapsed()}
 * 在旧实现下会红。</p>
 *
 * <p>纯 Mockito：Redis / DB / 审核信号全部 mock，无外部依赖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedTimelineStoreReadPagePreRankTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final long BASE_MILLIS = 1_700_000_000_000L;

    @Mock
    private StringRedisTemplate redisTemplate;
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

    // ===== 构造 =====

    /**
     * 构造 store：{@code poolReadWeightEnabled} 与池权重是 {@code @Value} 字段，
     * new 出来不会注入，必须反射设置——否则走「全池合并 + 全局倒序」回退分支，粗排根本不执行。
     */
    private FeedTimelineStore store(PreRankFilter preRankFilter) {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSet);
        // A7：漏斗编排在 RecommendationPipeline 里，粗排也在其中，故这里构造真实的 pipeline
        // （不是 mock），否则测不到"粗排 × 分页"的组合行为——那正是本文件要守护的地方。
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
        RecommendationPipeline pipeline = new RecommendationPipeline(followService, sessionSequenceService,
                reviewSignalClient, moderationProperties, realtimeFeatureService,
                preRankFilter, recallSupport, recallChannels, rerankStages, interestService);
        // @Value 字段 new 出来不注入：poolWeightsCsv 需显式给出，其余召回配比保持 0（各路关闭，只留流量池）
        ReflectionTestUtils.setField(pipeline, "poolWeightsCsv", "0.2,0.3,0.5");
        FeedTimelineStore s = new FeedTimelineStore(redisTemplate, MAPPER, pipeline);
        // 同样是 @Value 字段：false 会走「全池合并 + 全局倒序」回退分支，粗排根本不执行
        ReflectionTestUtils.setField(s, "poolReadWeightEnabled", true);
        return s;
    }

    /** 读路径上的公共依赖：一律关掉/置零，让结果只由「池候选 + 粗排」决定。 */
    private void stubCommon() {
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of());
        lenient().when(interestService.hasInterest(anyString())).thenReturn(false);
        lenient().when(interestService.negativeTags(any())).thenReturn(Set.of());
        lenient().when(sessionSequenceService.recent(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(realtimeFeatureService.profileOf(any())).thenReturn(null);
        lenient().when(followService.hasFollows(anyString())).thenReturn(false);
        lenient().when(postStatService.snapshot(anyString()))
                .thenReturn(new PostStatService.PostStat(0, 0, 0, 0, 0, 0, 0));
        // 精排恒 0 分 → 稳定排序保持粗排给出的池内顺序，断言才可确定
        lenient().when(rankingModel.score(any(RankingFeatures.class))).thenReturn(0d);
        lenient().when(moderationProperties.isEnableRecallFilter()).thenReturn(false);
        lenient().when(moderationProperties.isEnableHealthDemotion()).thenReturn(false);
    }

    /** 造某池候选：count 条，入流 score 随序号递减（i 越大越旧）。 */
    private Set<ZSetOperations.TypedTuple<String>> tuplesOf(int pool, int count, List<String> tagsOfLast) {
        Set<ZSetOperations.TypedTuple<String>> set = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            long millis = BASE_MILLIS - (long) i * 1_000L;
            List<String> tags = (i == count - 1) ? tagsOfLast : List.of();
            FeedItemView item = new FeedItemView("post/" + pool + "/u" + i, "media/" + pool + "/u" + i,
                    "url", null, "APPROVED", Instant.ofEpochMilli(millis), "cap", null, tags);
            set.add(new DefaultTypedTuple<>(write(item), (double) millis));
        }
        return set;
    }

    private static String write(FeedItemView item) {
        try {
            return MAPPER.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 让 Redis 只在本日分桶返回池数据（读路径会并近 3 天，重复返回会导致条目翻倍）。 */
    private void stubPools(Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        lenient().when(zSet.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(),
                anyLong(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key == null || !key.endsWith(":" + today)) {
                return Set.of();
            }
            String[] parts = key.split(":");   // tf:feed:tl:{pool}:{date}
            return byPool.getOrDefault(Integer.parseInt(parts[3]), Set.of());
        });
    }

    private static PreRankFilter preRankFilter(double ratio, int minCandidates, int minPages) {
        RankingProperties props = new RankingProperties();
        props.setPreRankKeepRatio(ratio);
        props.setPreRankMinCandidates(minCandidates);
        props.setPreRankMinPages(minPages);
        return new PreRankFilter(new LinearPreRankModel(props), props);
    }

    private static List<String> keysOf(List<FeedItemView> items) {
        List<String> keys = new ArrayList<>(items.size());
        for (FeedItemView it : items) {
            keys.add(it.timelineKey());
        }
        return keys;
    }

    // ===== 用例 =====

    @Test
    void readPage_preRankEnabled_pageNotCollapsed() {
        Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int p = 1; p <= 3; p++) {
            byPool.put(p, tuplesOf(p, 60, List.of()));
        }
        stubPools(byPool);
        stubCommon();

        // minPages=1 → 每池保留 max(20×1, ceil(60×0.3)=18) = 20 条（截断真的生效）
        List<FeedItemView> page = store(preRankFilter(0.3d, 50, 1)).readPage("u1", 0, 20);

        // 旧实现（跨池 Top-N 过滤当页切片）此处只有 0~2 条：这是本用例存在的原因
        assertEquals(20, page.size(), "开启粗排后单页仍应填满，不能塌缩");
    }

    @Test
    void readPage_tinyRatio_stillFillsPage_thanksToMinPagesFloor() {
        Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int p = 1; p <= 3; p++) {
            byPool.put(p, tuplesOf(p, 60, List.of()));
        }
        stubPools(byPool);
        stubCommon();

        // ratio=0.01 → 按比例只有 ceil(60×0.01)=1 条；靠下限 limit×minPages=20×2=40 兜底（再 min 到池大小 60）
        List<FeedItemView> page = store(preRankFilter(0.01d, 50, 2)).readPage("u1", 0, 20);

        // 旧实现没有下限：keep = floor(20×0.01) = 0 → max(1,·) = 1，全池只留 1 条，单页必然塌缩
        assertEquals(20, page.size(), "保留比例极小时也应靠下限填满单页");
    }

    @Test
    void readPage_preRankEnabled_interestHitEntersFirstPageDespiteOldRecency() {
        Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        // 池 3 共 60 条，最后一条最旧、但带用户兴趣标签「美食」
        byPool.put(3, tuplesOf(3, 60, List.of("美食")));
        stubPools(byPool);
        stubCommon();
        when(interestService.hasInterest("u1")).thenReturn(true);
        // 用 anyInt 匹配 INTEREST_TOP_N：写死常量会让 stub 悄悄失配（兴趣画像为空 → 粗排退化成纯 recency）
        when(interestService.weightedTags(eq("u1"), anyInt())).thenReturn(Map.of("美食", 1.0d));
        when(interestService.shortTermTags(eq("u1"), anyInt())).thenReturn(Map.of());

        RankingProperties props = new RankingProperties();
        props.setPreRankKeepRatio(0.3d);
        props.setPreRankMinCandidates(50);
        props.setPreRankMinPages(1);
        props.setInterestWeight(20.0d);   // 放大兴趣信号，确保能顶过 recency 差距（池内只差 59 秒）
        PreRankFilter filter = new PreRankFilter(new LinearPreRankModel(props), props);

        List<String> keys = keysOf(store(filter).readPage("u1", 0, 20));

        // 按旧逻辑（切片只看入流时刻）这条最旧的内容根本进不了首屏
        assertTrue(keys.contains("post/3/u59"),
                "兴趣命中但入流较旧的内容应能靠粗排进首屏，实际首屏=" + keys);
    }

    @Test
    void readPage_smallPool_skipsPreRank_identicalToDisabled() {
        Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int p = 1; p <= 3; p++) {
            byPool.put(p, tuplesOf(p, 10, List.of()));   // 10 条 < minCandidates(50)
        }
        stubPools(byPool);
        stubCommon();

        List<String> withPreRankOn = keysOf(store(preRankFilter(0.3d, 50, 1)).readPage("u1", 0, 20));
        List<String> withPreRankOff = keysOf(store(preRankFilter(0.0d, 50, 1)).readPage("u1", 0, 20));

        assertEquals(withPreRankOff, withPreRankOn, "池候选低于阈值时粗排应完全不介入（零变化）");
    }

    @Test
    void readPage_preRankDisabled_keepsBaselineOrder() {
        Map<Integer, Set<ZSetOperations.TypedTuple<String>>> byPool = new LinkedHashMap<>();
        for (int p = 1; p <= 3; p++) {
            byPool.put(p, tuplesOf(p, 60, List.of()));
        }
        stubPools(byPool);
        stubCommon();

        List<String> keys = keysOf(store(preRankFilter(0.0d, 50, 1)).readPage("u1", 0, 20));

        // 关闭时按池权重切片、池内按入流时刻：池 3 最新 10 条 + 池 2 最新 6 条 + 池 1 最新 4 条
        assertEquals(20, keys.size());
        assertTrue(keys.contains("post/3/u0"), "关闭粗排时应仍是入流最新者先进首屏");
    }
}
