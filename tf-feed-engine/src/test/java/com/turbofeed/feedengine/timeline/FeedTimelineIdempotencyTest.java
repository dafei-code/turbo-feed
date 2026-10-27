package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.PreRankFilter;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.feedengine.ranking.RealtimeFeatureService;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.social.FollowService;
import com.turbofeed.feedengine.timeline.ColdStartService;
import com.turbofeed.feedengine.timeline.DiversityRerankProperties;
import com.turbofeed.feedengine.timeline.DiversityRerankService;
import com.turbofeed.feedengine.timeline.EcosystemRegulationService;
import com.turbofeed.feedengine.timeline.PostStatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1-PR01：幂等键分库覆盖核验与加固（写路径数据级不变式）。
 *
 * <p>原计划 PR-01 的诉求是「128 分库（ShardingSphere {@code hashMod128}）下同一内容不因重复过审 /
 * 申诉翻案而被物化到多条分片记录」。公域时间线读模型是 Redis ZSET（非 MySQL 分片），其幂等保证
 * 落在 {@link FeedTimelineStore#appendStrict} 的「先 {@code detach} 摘旧位置 + 再 {@code ZADD} 新位置
 * + 覆盖写反查索引 {@code tf:feed:idx:{timelineKey}}」三连击上：</p>
 * <ul>
 *   <li>ZSET 成员串唯一 → 同 member 重复 {@code ZADD} 是替换而非新增（不会在同一桶里出现两份）；</li>
 *   <li>反查索引是单值 KV（每次 {@code set} 覆盖，不是追加）→ 一个帖身份永远只有一条定位；</li>
 *   <li>{@code detach} 据反查索引精确 {@code ZREM} 旧位置（含跨日桶 / 跨池）→ 重复过审不会在旧桶残留。</li>
 * </ul>
 *
 * <p><b>为什么用内存 fake 而不是数 mock 调用</b>：Mockito 只能验证「调用发生了」，无法验证 ZSET
 * 成员唯一、反查索引单点这些<b>数据级</b>不变量——而后者才是「不重复物化」的真正保证。本测试用一个
 * 模仿 ZSET 成员唯一 + Value 单值语义的轻量 fake（不启 Spring / 不需要 Docker），断言 append /
 * remove 之后的<b>最终 Redis 状态</b>：同一 {@code timelineKey} 在任意桶里至多一个 member、反查索引
 * 单点、作者索引单点；不同键互不污染；下架后完全可逆（桶空 + 索引删 + 作者索引空）。</p>
 *
 * <p>数据级不变量一旦成立，迁移到 128 分库的等价语义（按 {@code timelineKey.hashCode() % 128} 路由）
 * 就天然无跨库重复——因为幂等键在写入侧已保证「一个帖身份一份物化」。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineIdempotencyTest {

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

    // ---- 内存 fake：模拟 ZSET 成员唯一 + Value 单值语义 ----
    private final Map<String, Map<String, Double>> zsets = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();

    private final ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 构造与生产一致的 store，并把 zSet/valueOps 的操作接到内存 fake。 */
    private FeedTimelineStore store() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSet);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // ZADD：ZSET 成员唯一——已存在的 member 替换 score（不新增）。返回 1L 表示已写入。
        lenient().doAnswer(a -> {
            String key = a.getArgument(0);
            String member = a.getArgument(1);
            double score = a.getArgument(2);
            zsets.computeIfAbsent(key, k -> new HashMap<>()).put(member, score);
            return Boolean.TRUE;
        }).when(zSet).add(anyString(), anyString(), anyDouble());
        // ZREM：从桶移除 member。
        lenient().doAnswer(a -> {
            Map<String, Double> m = zsets.get(a.getArgument(0));
            if (m != null) {
                m.remove(a.getArgument(1));
            }
            return 1L;
        }).when(zSet).remove(anyString(), anyString());
        // ZSCORE：取成员分数（反查索引与 ZSET 不一致时返回 null）。
        lenient().when(zSet.score(anyString(), anyString())).thenAnswer(a -> {
            Map<String, Double> m = zsets.get(a.getArgument(0));
            return m == null ? null : m.get(a.getArgument(1));
        });
        // reverseRangeByScore：idempotency 用例走 detach 精确路径，不触扫描；返回空集兜底。
        lenient().when(zSet.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of());
        // valueOps.set：单值 KV，覆盖写（反查索引单点的来源）。
        lenient().doAnswer(a -> {
            values.put(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        lenient().when(valueOps.get(anyString())).thenAnswer(a -> values.get(a.getArgument(0)));
        // delete：删除反查索引（removeStrict 成功路径）。
        lenient().doAnswer(a -> {
            values.remove(a.getArgument(0));
            return null;
        }).when(redisTemplate).delete(anyString());
        // expire：fake 无需真实 TTL。
        lenient().doAnswer(a -> null).when(redisTemplate).expire(anyString(), any(Duration.class));
        return new FeedTimelineStore(redisTemplate, objectMapper, recommendationPipeline);
    }

    /** 一个帖（postId 决定 timelineKey = post/123/u1，作者解析为 123）。 */
    private FeedItemView approvedItem() {
        return new FeedItemView("post/123/u1", "media/123/x.jpg", "url", null,
                "APPROVED", Instant.now(), "cap", null, null);
    }

    private String bucket(int pool) {
        return TimelineSupport.bucketKey(pool, TimelineSupport.dateOf(Instant.now()));
    }

    private String indexKey() {
        return TimelineSupport.indexKey("post/123/u1");
    }

    private String authorKey() {
        return TimelineSupport.AUTHOR_PREFIX + "123";
    }

    /** 同一 item 序列化出的成员串（与 store 内部序列化一致，用于预播种旧位置）。 */
    private String memberOf(FeedItemView item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 核心不变式 ====================

    @Test
    void repeatedAppend_sameTimelineKey_materializesExactlyOneMember() {
        FeedTimelineStore store = store();
        FeedItemView item = approvedItem();

        // 重复过审 / 申诉翻案：同一帖身份被 append 两次（甚至多次）。
        store.appendStrict(item, 2);
        store.appendStrict(item, 2);
        store.appendStrict(item, 2);

        // 池桶里该帖只占一个 member（ZSET 唯一 + detach 摘旧，绝不重复物化）。
        Map<String, Double> bucketMembers = zsets.get(bucket(2));
        assertNotNull(bucketMembers, "帖应落在 L2 桶");
        assertEquals(1, bucketMembers.size(), "同一 timelineKey 重复 append 不应产生重复 member");

        // 反查索引单点：始终只有一条定位，且指向当前桶。
        String idx = values.get(indexKey());
        assertNotNull(idx, "反查索引必须存在");
        assertTrue(idx.startsWith(bucket(2) + TimelineSupport.IDX_SEP), "反查索引应指向当前桶");

        // 作者索引单点：关注流不会因重复过审而重复出现。
        Map<String, Double> authorMembers = zsets.get(authorKey());
        assertNotNull(authorMembers);
        assertEquals(1, authorMembers.size(), "作者索引不应重复");
    }

    @Test
    void reAuditInDifferentDayBucket_detachesOldBeforeAddingNew_noCrossBucketDuplicate() {
        FeedTimelineStore store = store();
        FeedItemView item = approvedItem();
        String member = memberOf(item);

        // 预播种：上次过审落在更早的日桶 tf:feed:tl:2:20261010（模拟跨日 / 扩容前的旧物化）。
        String oldBucket = "tf:feed:tl:2:20261010";
        zsets.computeIfAbsent(oldBucket, k -> new HashMap<>()).put(member, 1000.0);
        values.put(indexKey(), TimelineSupport.indexValue(oldBucket, member));

        // 今天再次过审（新日桶）：append 必须先把旧桶里的 member 摘掉，再写新桶。
        store.appendStrict(item, 2);

        // 旧桶被精确摘除 → 空；新桶恰一个 member → 跨桶无重复。
        Map<String, Double> oldMembers = zsets.get(oldBucket);
        assertTrue(oldMembers == null || oldMembers.isEmpty(),
                "旧日桶的 member 应被 detach 摘除，否则跨桶重复物化");

        Map<String, Double> newMembers = zsets.get(bucket(2));
        assertNotNull(newMembers, "应落到当天桶");
        assertEquals(1, newMembers.size(), "新桶恰一个 member");

        // 反查索引改写成新桶位置（否则后续下架会 ZREM 已空的旧桶，下架失效）。
        String idx = values.get(indexKey());
        assertNotNull(idx);
        assertTrue(idx.startsWith(bucket(2) + TimelineSupport.IDX_SEP),
                "反查索引应改写到当天新桶");
    }

    @Test
    void appendThenRemoveStrict_leavesNoMemberAndNoIndex_fullyReversible() {
        FeedTimelineStore store = store();
        FeedItemView item = approvedItem();

        store.appendStrict(item, 1);
        assertTrue(zsets.get(bucket(1)) != null && zsets.get(bucket(1)).size() == 1,
                "append 后桶里应有一个 member");
        assertNotNull(values.get(indexKey()), "append 后反查索引应存在");

        // 下架 / 用户删除：removeStrict 经反查索引精确摘除。
        store.removeStrict("post/123/u1");

        Map<String, Double> bucketMembers = zsets.get(bucket(1));
        assertTrue(bucketMembers == null || bucketMembers.isEmpty(),
                "下架后桶里不应残留 member（否则内容仍可见）");
        assertNull(values.get(indexKey()), "下架后反查索引应被删除");

        Map<String, Double> authorMembers = zsets.get(authorKey());
        assertTrue(authorMembers == null || authorMembers.isEmpty(),
                "下架后作者索引不应残留（否则仍出现在关注流）");
    }

    @Test
    void differentTimelineKeys_coexistAndDoNotCrossContaminate() {
        FeedTimelineStore store = store();
        FeedItemView a = new FeedItemView("post/123/u1", "media/123/x.jpg", "url", null,
                "APPROVED", Instant.now(), "capA", null, null);
        FeedItemView b = new FeedItemView("post/456/u2", "media/456/y.jpg", "url", null,
                "APPROVED", Instant.now(), "capB", null, null);

        // A、B 各 append 一次，再重复 append A（模拟 A 重复过审）。
        store.appendStrict(a, 2);
        store.appendStrict(b, 2);
        store.appendStrict(a, 2);

        // A 的桶：1 个 member（重复过审未翻倍）；B 的桶：1 个 member；互不串。
        Map<String, Double> bucketA = zsets.get(bucket(2));
        assertNotNull(bucketA);
        assertEquals(2, bucketA.size(), "A、B 同桶共存，桶里共两个 member");
        long countA = bucketA.keySet().stream().filter(k -> k.equals(memberOf(a))).count();
        assertEquals(1, countA, "A 不应因重复过审而重复物化");
        long countB = bucketA.keySet().stream().filter(k -> k.equals(memberOf(b))).count();
        assertEquals(1, countB, "B 恰一个 member");

        // 反查索引各一份、指向各自 member。
        String idxA = values.get(TimelineSupport.indexKey("post/123/u1"));
        String idxB = values.get(TimelineSupport.indexKey("post/456/u2"));
        assertNotNull(idxA);
        assertNotNull(idxB);
        assertTrue(idxA.contains(memberOf(a)), "A 的反查索引应指向 A 的 member");
        assertTrue(idxB.contains(memberOf(b)), "B 的反查索引应指向 B 的 member");
        assertFalse(idxA.contains(memberOf(b)), "A 的索引不应串到 B");
    }

    @Test
    void appendStrict_ignoresUnapproved_noMaterialization() {
        FeedTimelineStore store = store();
        FeedItemView pending = new FeedItemView("post/999/u9", "media/999/z.jpg", "url", null,
                "PENDING", Instant.now(), "cap", null, null);

        store.appendStrict(pending, 1);

        // 未过审不入任何池、不写反查索引、不写作者索引。
        assertTrue(zsets.get(bucket(1)) == null || zsets.get(bucket(1)).isEmpty());
        assertNull(values.get(TimelineSupport.indexKey("post/999/u9")));
        assertTrue(zsets.get(TimelineSupport.AUTHOR_PREFIX + "999") == null
                || zsets.get(TimelineSupport.AUTHOR_PREFIX + "999").isEmpty());
    }
}
