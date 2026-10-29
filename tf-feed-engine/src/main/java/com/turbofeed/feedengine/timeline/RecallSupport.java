package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.feedengine.contract.ItemViewCodec;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedFilterChain;
import com.turbofeed.feedengine.ranking.FeedFilterContext;
import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.feedengine.ranking.RankingFeatures;
import com.turbofeed.feedengine.ranking.RankingModel;
import com.turbofeed.feedengine.ranking.UserRealtimeProfile;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.AUTHOR_PREFIX;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MAX_POOL_LEVELS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.MERGE_BUCKETS;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.bucketKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.dateOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.indexKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.isModerationBlocked;
import static com.turbofeed.feedengine.timeline.TimelineSupport.matchesNegative;
import static com.turbofeed.feedengine.timeline.TimelineSupport.parseIndex;

/**
 * 召回 / 重排共享助手（P1 组件化抽出）。
 *
 * <p>原 {@link RecommendationPipeline} 内联的「评分 / 还原条目 / 取作者近期内容 / 冷启动候选 / 审核过滤」
 * 纯函数与 I/O 片段，统一收敛到本类，供 {@link RecallChannel} / {@link RerankStage} 复用——
 * 既消除重复，也让各组件不必各自持有全套依赖。行为与原内联实现逐行一致。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecallSupport {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PostStatService postStatService;
    private final RankingModel rankingModel;
    private final ReviewSignalClient reviewSignalClient;
    private final FeedModerationProperties moderationProperties;
    private final FeedFilterChain feedFilterChain;

    // ===== 解析 / 还原 =====

    FeedItemView parseMember(String json) {
        return ItemViewCodec.parse(objectMapper, json);
    }

    /** 经反查索引还原完整条目（热点/兴趣召回用）；失效/损坏返回 {@code null}（fail-open）。 */
    FeedItemView memberOf(String timelineKey) {
        if (timelineKey == null) {
            return null;
        }
        try {
            TimelineSupport.IndexEntry entry = parseIndex(redisTemplate.opsForValue().get(indexKey(timelineKey)));
            if (entry == null) {
                return null;
            }
            return parseMember(entry.member());
        } catch (Exception e) {
            log.warn("召回还原条目失败（跳过该条）: timelineKey={}, {}", timelineKey, e.getMessage());
            return null;
        }
    }

    /** 关注作者近期已审内容（关注流 G6 召回用）；fail-open 返回空集。 */
    List<FeedItemView> authorRecentItems(String authorId, int cap, FeedCursor cursor) {
        if (authorId == null || authorId.isEmpty() || cap <= 0) {
            return List.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(AUTHOR_PREFIX + authorId,
                            Double.NEGATIVE_INFINITY,
                            (cursor == null) ? Double.POSITIVE_INFINITY : cursor.score(), 0, cap);
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

    /** 冷启动探索候选（G7）：试水池内入流时刻晚于 {@code threshold} 的新鲜内容，最多 {@code budget} 条；fail-open 空集。 */
    List<FeedItemView> collectColdStartCandidates(int budget, Instant threshold, int maxPool) {
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
                    String date = dateOf(today.minusDays(d));
                    String key = bucketKey(pool, date);
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

    // ===== 评分 =====

    /** 组装排序特征并交 {@link RankingModel} 打分（原 scoreOf 逻辑，逐候选复用 ctx 内画像/会话/实时特征）。 */
    double scoreOf(ScoredItem s, RecallContext ctx) {
        PostStatService.PostStat stat = new PostStatService.PostStat(0, 0, 0, 0, 0, 0, 0);
        try {
            stat = postStatService.snapshot(s.item().timelineKey());
        } catch (Exception ignore) {
            // 统计不可用：按零计数处理——平滑后自然回落到先验水平，而不是朴素比率的 0
        }
        double interestScore = 0d;
        double shortTermScore = 0d;
        if (!ctx.interest.isEmpty()) {
            List<String> tags = s.item().tags();
            if (tags != null) {
                for (String tag : tags) {
                    Double w = ctx.interest.get(tag);
                    if (w != null) {
                        interestScore += w;
                    }
                    Double sw = ctx.shortInterest.get(tag);   // 同源标签：长期命中即看短期层有没有"当下追更"
                    if (sw != null) {
                        shortTermScore += sw;
                    }
                }
            }
        }
        // session 级 attention：候选标签与"最近互动过的内容"重合度 × 那些互动的时效权重之和。
        double sessionMatch = 0d;
        List<String> ctags = s.item().tags();
        if (ctags != null && !ctags.isEmpty() && !ctx.session.isEmpty()) {
            for (SessionSequenceService.SessionItem it : ctx.session) {
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
        // 作者健康分排序系数（抖音式「审核与推荐解耦」接入点）：懒读 tf:mod:account 并按本页缓存。
        double healthScale = 1.0d;
        if (moderationProperties.isEnableHealthDemotion()) {
            String aid = authorIdOf(s.item());
            if (aid != null) {
                Double cached = ctx.authorHealthCache.get(aid);
                if (cached == null) {
                    cached = reviewSignalClient.healthScaleOf(aid);
                    ctx.authorHealthCache.put(aid, cached);
                }
                healthScale = cached;
            }
        }
        // 实时特征匹配（抖音式「实时特征流」）。
        double realtimeMatch = 0d;
        if (ctx.realtimeProfile != null && !ctx.realtimeProfile.isEmpty()) {
            List<String> rtags = s.item().tags();
            if (rtags != null) {
                Map<String, Double> aff = ctx.realtimeProfile.tagAffinity();
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
                matchesNegative(s.item(), ctx.negative),
                healthScale,
                realtimeMatch));
    }

    /** 召回通道内部按精排分降序（scoreOf 恒 0 时保持插入序，断言可确定）。 */
    void sortByScore(List<ScoredItem> part, RecallContext ctx) {
        part.sort(Comparator.comparingDouble((ScoredItem a) -> scoreOf(a, ctx)).reversed());
    }

    // ===== 审核过滤 =====

    /**
     * 候选是否通过推荐过滤（P2-PR06 规则链）。
     *
     * <p>短路语义：被审核拦截的内容不占去重名额（与旧 {@code isModerationBlocked || !dedup.accept}
     * 短路一致）；开启新链时由 {@code ModerationBlockRule} 的 {@code @Order(1)} 保证同序。</p>
     */
    boolean passFilters(FeedItemView item, RecallContext ctx) {
        if (ctx.filterChainEnabled) {
            return feedFilterChain.allow(item, ctx.filterCtx);
        }
        return !isModerationBlocked(item, ctx.filterCtx.blockedPosts(), ctx.filterCtx.blockedAuthors())
                && ctx.filterCtx.dedup().accept(item);
    }
}
