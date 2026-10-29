package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

/**
 * 记忆式 item-CF 召回（抖音式多路召回的「收藏又收藏 / 看了又看」补充通道，P1）。
 *
 * <p><b>数据来源</b>：以用户近期收藏（{@code tf:favorite:user:{userId}}，网关写、本通道只读）为<b>种子</b>，
 * 查每篇种子内容的共现邻居 ZSET {@code tf:cf:item:{seed} => ZSET<neighbor, coocWeight>}
 * （由网关 {@code ItemCooccurrenceService} 增量维护），merge 后取 Top-N 作为候选。</p>
 *
 * <p><b>与双塔 / A1 的关系</b>：item-CF 是<b>记忆式</b>共现（可解释、零训练延迟），与向量召回（语义相近）
 * 互补而非替代——前者捞「和你收藏过的东西强共现」的内容，后者捞「语义相近但未必同收藏」的内容。
 * 二者都作为个性化位并入多路召回，最终由 RankingModel 统一排序。</p>
 *
 * <p><b>排除集</b>：本通道在 PRE_POOL 运行（热点 / 兴趣之后），排除 hotKeys / interestKeys /
 * followeeAuthors（同页个性化位不重复）；vectorKeys 尚未生成（POST_POOL 之后才跑），其重叠由
 * 装配期的整页去重（{@code PageDedupRule}）兜底。候选写入 cfKeys 供流量池解析与向量召回去重。</p>
 *
 * <p><b>fail-open</b>：种子 / 共现读取任一异常 → 返回空集，退化为「无 CF 信号」，不阻断推荐主流程。</p>
 */
@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class ItemCfRecallChannel implements RecallChannel {

    /** 单次召回最多取的用户收藏种子数（控制共现查询量）。 */
    private static final int SEED_LIMIT = 20;
    /** 每篇种子内容最多取的共现邻居数（控制单帖查询量）。 */
    private static final int NEIGHBOR_LIMIT = 50;

    private final StringRedisTemplate redisTemplate;
    private final RecallSupport recallSupport;

    @Override
    public RecallType type() {
        return RecallType.CF;
    }

    @Override
    public RecallPhase phase() {
        return RecallPhase.PRE_POOL;
    }

    @Override
    public List<ScoredItem> recall(RecallContext ctx, int slots) {
        if (slots <= 0 || ctx.userId == null) {
            return List.of();
        }
        // 种子：用户近期收藏（score=收藏时刻，倒序取最新 SEED_LIMIT 篇）
        Set<String> seeds;
        try {
            Set<String> s = redisTemplate.opsForZSet()
                    .reverseRange(RedisKeys.userFavorite(Long.parseLong(ctx.userId)), 0, SEED_LIMIT - 1);
            seeds = (s == null) ? Set.of() : s;
        } catch (Exception e) {
            log.warn("item-CF 读取用户收藏种子失败（fail-open 退化为无信号）: userId={}, {}",
                    ctx.userId, e.getMessage());
            return List.of();
        }
        if (seeds.isEmpty()) {
            return List.of(); // 无收藏即无共现种子
        }
        // merge 共现邻居：neighbor → 累计共现权重
        Map<String, Double> merged = new LinkedHashMap<>();
        try {
            for (String seed : seeds) {
                Set<ZSetOperations.TypedTuple<String>> neighbors = redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(RedisKeys.cfItem(seed),
                                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, NEIGHBOR_LIMIT - 1);
                if (neighbors == null) {
                    continue;
                }
                for (ZSetOperations.TypedTuple<String> t : neighbors) {
                    String nb = t == null ? null : t.getValue();
                    if (nb == null || seeds.contains(nb)) {
                        continue; // 跳过种子自身（已收藏的不必再推）
                    }
                    Double w = t == null ? null : t.getScore();
                    merged.merge(nb, w == null ? 0d : w, Double::sum);
                }
            }
        } catch (Exception e) {
            log.warn("item-CF 读取共现邻居失败（fail-open 退化为无信号）: userId={}, {}",
                    ctx.userId, e.getMessage());
            return List.of();
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        // 按累计权重降序取候选，排除其他个性化位已占用的，写回 cfKeys
        List<ScoredItem> part = new ArrayList<>();
        Set<String> cfKeys = ctx.cfKeys;
        Set<String> hotKeys = ctx.hotKeys;
        Set<String> interestKeys = ctx.interestKeys;
        merged.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> {
                    if (part.size() >= slots) {
                        return;
                    }
                    String tk = e.getKey();
                    if (tk == null || !cfKeys.add(tk) || hotKeys.contains(tk) || interestKeys.contains(tk)) {
                        return;
                    }
                    FeedItemView it = recallSupport.memberOf(tk);
                    if (it == null) {
                        return;
                    }
                    // 关注作者内容只在首屏关注位曝光（与热点/兴趣/向量/流量池同款排除）
                    // authorIdOf 解析失败返回 null（fail-open：不据此排除），故先判空
                    String aid = authorIdOf(it);
                    if (aid != null && ctx.followeeAuthors.contains(aid)) {
                        return;
                    }
                    part.add(new ScoredItem(it, recencyOf(it)));
                });
        // 个性化位内部仍按精排分排序：召回只保证"进得来"，排序权交给 RankingModel。
        recallSupport.sortByScore(part, ctx);
        return part;
    }
}
