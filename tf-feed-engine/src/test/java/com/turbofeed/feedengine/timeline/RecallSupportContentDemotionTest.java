package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.ranking.FeedFilterChain;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.RankingFeatures;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecallSupport#scoreOf} 的审核综合降权系数单测（ADR-0014）。
 *
 * <p>锁死「作者级系数 × 内容级系数」这一个乘性通道：内容 DEMOTE 只在精排生效，
 * 与召回过滤（INTERCEPT/MONITOR 硬剔除）正交；两个开关各自独立；同一 postId 一页内只读一次 Redis。</p>
 *
 * <p>纯 Mockito：不跑漏斗、不连 Redis，直接捕获传给 {@link RankingModel} 的
 * {@link RankingFeatures#authorHealthScale()}（字段名沿用历史，语义已是综合系数）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RecallSupportContentDemotionTest {

    private static final String KEY = "post/42/u1";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ObjectMapper objectMapper;
    @Mock PostStatService postStatService;
    @Mock RankingModel rankingModel;
    @Mock ReviewSignalClient reviewSignalClient;
    @Mock FeedModerationProperties moderationProperties;
    @Mock FeedFilterChain feedFilterChain;

    private RecallSupport support;
    private RecallContext ctx;

    @BeforeEach
    void setUp() {
        support = new RecallSupport(redisTemplate, objectMapper, postStatService, rankingModel,
                reviewSignalClient, moderationProperties, feedFilterChain);
        ctx = new RecallContext(null, 0, 20, null, Map.of(), Map.of(),
                List.of(), Set.of(), null, Set.of(), Map.of(), support);
        // scoreOf 的无关依赖：零统计、精排分固定为 1.0
        lenient().when(postStatService.snapshot(anyString()))
                .thenReturn(new PostStatService.PostStat(0, 0, 0, 0, 0, 0, 0));
        lenient().when(rankingModel.score(any(RankingFeatures.class))).thenReturn(1.0d);
        // 默认：作者级降权关、内容级降权开且命中 DEMOTE(0.7)
        lenient().when(moderationProperties.isEnableHealthDemotion()).thenReturn(false);
        lenient().when(moderationProperties.isEnableContentDemotion()).thenReturn(true);
        lenient().when(reviewSignalClient.contentDemotionScaleOf(anyString())).thenReturn(0.7d);
        lenient().when(reviewSignalClient.healthScaleOf(anyString())).thenReturn(0.5d);
    }

    private static FeedItemView item() {
        return new FeedItemView(KEY, "m:" + KEY, "url", null,
                FeedItemView.STATUS_APPROVED, Instant.now(), "cap", null, List.of());
    }

    private void score() {
        support.scoreOf(new ScoredItem(item(), 100d), ctx);
    }

    private RankingFeatures lastFeatures(int expectedCalls) {
        ArgumentCaptor<RankingFeatures> cap = ArgumentCaptor.forClass(RankingFeatures.class);
        verify(rankingModel, times(expectedCalls)).score(cap.capture());
        return cap.getValue();
    }

    @Test
    void contentDemotion_appliesContentScale_alreadyInRankingFeatures() {
        score();

        assertEquals(0.7d, lastFeatures(1).authorHealthScale(), 1e-9);
        verify(reviewSignalClient, times(1)).contentDemotionScaleOf(KEY);
    }

    @Test
    void contentDemotion_multipliesWithAuthorHealthScale() {
        when(moderationProperties.isEnableHealthDemotion()).thenReturn(true);

        score();

        // 作者级 0.5 × 内容级 0.7 = 0.35；二者是同一个乘性通道而非二选一
        assertEquals(0.35d, lastFeatures(1).authorHealthScale(), 1e-9);
    }

    @Test
    void contentDemotion_switchOff_leavesScaleOne_withoutRedisRead() {
        when(moderationProperties.isEnableContentDemotion()).thenReturn(false);

        score();

        assertEquals(1.0d, lastFeatures(1).authorHealthScale(), 1e-9);
        verify(reviewSignalClient, never()).contentDemotionScaleOf(anyString());
    }

    @Test
    void contentDemotion_bothSignalsFailOpen_returnsOne() {
        when(moderationProperties.isEnableHealthDemotion()).thenReturn(true);
        when(reviewSignalClient.healthScaleOf(anyString())).thenReturn(1.0d);
        when(reviewSignalClient.contentDemotionScaleOf(anyString())).thenReturn(1.0d);

        score();

        assertEquals(1.0d, lastFeatures(1).authorHealthScale(), 1e-9);
    }

    @Test
    void contentDemotion_cachedWithinPage_redisReadOnceForRepeatedScoreOf() {
        score();
        score();

        // 召回通道内部排序与池内切片排序都会调 scoreOf，同一 postId 一页内只打一次 Redis
        assertEquals(0.7d, lastFeatures(2).authorHealthScale(), 1e-9);
        verify(reviewSignalClient, times(1)).contentDemotionScaleOf(KEY);
    }
}
