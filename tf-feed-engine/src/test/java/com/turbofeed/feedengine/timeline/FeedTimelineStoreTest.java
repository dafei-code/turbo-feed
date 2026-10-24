package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.PreRankFilter;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.feedengine.ranking.RealtimeFeatureService;
import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.feedengine.social.FollowService;
import com.turbofeed.feedengine.timeline.ColdStartService;
import com.turbofeed.feedengine.timeline.DiversityRerankProperties;
import com.turbofeed.feedengine.timeline.DiversityRerankService;
import com.turbofeed.feedengine.timeline.EcosystemRegulationService;
import com.turbofeed.feedengine.timeline.PostStatService;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心②：时间线写路径（{@link FeedTimelineStore#append} / {@link #promote} / {@link #remove}）。
 *
 * <p>纯 Mockito 单测，不启 Spring、不需要 Docker——只 mock {@code StringRedisTemplate} 的
 * ZSet / Value 操作，验证「写入打到正确的池桶键 + 反查索引 + 作者索引」「晋级仅升不降并改写索引」
 * 「下架经反查索引精确 ZREM」。这些是顺序消息保序、下架不复活的正确性底座。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock PostStatService postStatService;
    @Mock HotTimelineKeyCache hotTimelineKeyCache;
    @Mock InterestService interestService;
    @Mock SessionSequenceService sessionSequenceService;
    @Mock RankingModel rankingModel;
    @Mock ReviewSignalClient reviewSignalClient;
    @Mock FeedModerationProperties moderationProperties;
    @Mock VectorRecallChannel vectorRecallChannel;
    @Mock RealtimeFeatureService realtimeFeatureService;
    @Mock PreRankFilter preRankFilter;
    @Mock RecommendationPipeline recommendationPipeline;
    @Mock DiversityRerankProperties diversityRerankProperties;
    @Mock DiversityRerankService diversityRerankService;
    @Mock ColdStartService coldStartService;
    @Mock FollowService followService;
    @Mock EcosystemRegulationService ecosystemRegulationService;

    private final ZSetOperations<String, String> zSet = mock();
    private final ValueOperations<String, String> valueOps = mock();

    private FeedTimelineStore store() {
        // 与生产一致：注册 JavaTimeModule，否则 Instant createdAt 序列化抛异常（append 的 fail-open 会吞掉、不写 Redis）。
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // lenient：store() 每次都 stub 两个 ops，部分用例只用其一，严格模式会报 UnnecessaryStubbing。
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSet);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 热点榜 L1 在测试里不命中（无真实热点数据），返回空，热点召回贡献为空（与原行为一致）。
        lenient().when(hotTimelineKeyCache.getHotKeys(anyInt(), any())).thenReturn(List.of());
        // A7 之后本类只保留读模型职责（池 ZSET + 索引 + 游标），漏斗编排已迁至 RecommendationPipeline。
        // 写路径用例不触达漏斗，故 pipeline 用 mock 即可。
        return new FeedTimelineStore(redisTemplate, objectMapper, recommendationPipeline);
    }

    private FeedItemView approvedItem() {
        // postId=post/123/u1 → 时间线键 post/123/u1，作者解析为 123
        return new FeedItemView("post/123/u1", "media/123/x.jpg", "url", null,
                "APPROVED", Instant.now(), "cap", null, null);
    }

    @Test
    void append_writesPoolBucketAndReverseIndex_andAuthorIndex() {
        FeedTimelineStore store = store();
        FeedItemView item = approvedItem();

        store.append(item, 2);

        // 池桶 ZADD：键含 tf:feed:tl:2:
        verify(zSet).add(argThat(k -> k != null && k.contains("tf:feed:tl:2:")), anyString(), anyDouble());
        // 反查索引写入（供下架精确 ZREM）
        verify(valueOps).set(eq("tf:feed:idx:post/123/u1"), anyString(), any(Duration.class));
        // 作者索引 ZADD（G6 关注流）：作者 123
        verify(zSet).add(eq("tf:feed:author:123"), anyString(), anyDouble());
    }

    @Test
    void promote_movesFromOldPoolToTargetPool_onlyUp() {
        FeedTimelineStore store = store();
        String idxKey = "tf:feed:idx:post/123/u1";
        String location = "tf:feed:tl:1:20261017\u0001<member>";
        when(valueOps.get(idxKey)).thenReturn(location);
        when(zSet.score(anyString(), anyString())).thenReturn(1000.0);

        boolean promoted = store.promote("post/123/u1", 3);

        assertTrue(promoted);
        // 旧桶移除
        verify(zSet).remove(eq("tf:feed:tl:1:20261017"), anyString());
        // 新桶加入（同 score，仅池号 +1）
        verify(zSet).add(eq("tf:feed:tl:3:20261017"), anyString(), eq(1000.0));
        // 反查索引改写到新桶位置
        verify(valueOps).set(eq(idxKey), argThat(v -> v != null && v.contains("tf:feed:tl:3:20261017")),
                any(Duration.class));
    }

    @Test
    void promote_noIndex_returnsFalse() {
        FeedTimelineStore store = store();
        when(valueOps.get("tf:feed:idx:post/123/u1")).thenReturn(null);

        assertFalse(store.promote("post/123/u1", 3));
        verify(zSet, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void remove_detachesViaReverseIndex() {
        FeedTimelineStore store = store();
        String idxKey = "tf:feed:idx:post/123/u1";
        String location = "tf:feed:tl:1:20261017\u0001<member>";
        when(valueOps.get(idxKey)).thenReturn(location);

        store.remove("post/123/u1");

        // 旧桶 + 作者索引均精确 ZREM（下架不复活的关键）
        verify(zSet).remove(eq("tf:feed:tl:1:20261017"), anyString());
        verify(zSet).remove(eq("tf:feed:author:123"), anyString());
        verify(redisTemplate).delete(idxKey);
    }
}
