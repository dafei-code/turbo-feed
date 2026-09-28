package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户兴趣画像（Redis Hash {@code tf:user:interest:{userId}}：tag → 累计权重）。
 *
 * <p><b>累积来源</b>：行为埋点（点赞 / 评论 / 分享 / 完播 / 不感兴趣）。权重约定：
 * <ul>
 *   <li>{@code LIKE}=+1.0，{@code COMMENT}/{@code SHARE}=+1.5（强意图）；</li>
 *   <li>{@code WATCH}=+0.2（弱意图，看完即轻度感兴趣）；</li>
 *   <li>{@code DISLIKE}=-1.5（负向，降低该标签召回）；</li>
 *   <li>{@code IMPRESSION} 不计权重（曝光不表达偏好，避免刷屏内容吸走画像）。</li>
 * </ul>
 * 权重经 {@link TagIndexService#tagsOf} 把"被互动的内容"映射到其标签——埋点只带 timelineKey，
 * 不带标签，故必须反查标签索引。</p>
 *
 * <p><b>冷启动</b>：画像为空（新用户 / 未登录）时 {@link #weightedTags} 返回空 Map，
 * 推荐流退化为纯「入流时刻 + 完播率」排序（与改造前一致），绝不因缺画像而报错或空结果。</p>
 *
 * <p><b>衰减策略（MVP 简化）</b>：不做逐事件时间衰减，改用整份画像 TTL（{@code 30d}）——
 * 用户停更 30 天画像自动清零，重新活跃后从零累积。精确的"按事件龄指数衰减"留给后续
 * （需在 Hash 里再存时间戳，或引入 ZSET 按 score 衰减），本期不引入以保精简。</p>
 *
 * <p><b>fail-open</b>：Redis 异常只告警不抛，画像缺失最多让个性化失效（回退冷启动），不阻断浏览。</p>
 */
@Service
public class InterestService {

    private final StringRedisTemplate redisTemplate;
    private final TagIndexService tagIndexService;

    private static final String INTEREST_PREFIX = "tf:user:interest:";
    private static final Duration TTL = Duration.ofDays(30);

    /** 行为类型 → 兴趣权重（未知类型按 0 处理，不计画像）。 */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "LIKE", 1.0,
            "COMMENT", 1.5,
            "SHARE", 1.5,
            "WATCH", 0.2,
            "DISLIKE", -1.5);

    public InterestService(StringRedisTemplate redisTemplate, TagIndexService tagIndexService) {
        this.redisTemplate = redisTemplate;
        this.tagIndexService = tagIndexService;
    }

    /**
     * 处理一条行为事件：解析其标签并累加到对应用户画像（fail-open）。
     *
     * <p>匿名埋点（{@code userId == null}，理论不存在，因为上报接口要求登录）直接忽略；
     * 该内容无标签（{@code tagsOf} 为空）也忽略——没有标签就谈不上兴趣。</p>
     */
    public void accumulateFromEvent(FeedBehaviorEvent event) {
        if (event == null || event.userId() == null || event.timelineKey() == null) {
            return;
        }
        double weight = WEIGHTS.getOrDefault(event.type() == null ? "" : event.type().toUpperCase(), 0.0);
        if (weight == 0.0) {
            return; // 曝光等不计画像
        }
        List<String> tags = new ArrayList<>(tagIndexService.tagsOf(event.timelineKey()));
        if (tags.isEmpty()) {
            return;
        }
        try {
            String key = INTEREST_PREFIX + event.userId();
            for (String tag : tags) {
                redisTemplate.opsForHash().increment(key, tag, weight);
            }
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InterestService.class)
                    .warn("兴趣画像累积失败（不影响主流程）: userId={}, type={}, {}",
                            event.userId(), event.type(), e.getMessage());
        }
    }

    /**
     * 取用户兴趣 TopN 标签及其权重（仅正向权重用于召回/加权；负向不进召回）。
     *
     * @param userId 用户（{@code null} → 空 Map，冷启动）
     * @param n      返回条数上限（≤0 视为不限制）
     * @return tag → 累计权重（按权重降序）；无画像/异常返回空 Map
     */
    public Map<String, Double> weightedTags(String userId, int n) {
        if (userId == null) {
            return Map.of();
        }
        try {
            String key = INTEREST_PREFIX + userId;
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            List<Map.Entry<String, Double>> entries = new ArrayList<>();
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                double v = ((Number) e.getValue()).doubleValue();
                if (v > 0) {
                    entries.add(Map.entry(String.valueOf(e.getKey()), v));
                }
            }
            entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<String, Double> result = new LinkedHashMap<>();
            int limit = n <= 0 ? entries.size() : Math.min(n, entries.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Double> en = entries.get(i);
                result.put(en.getKey(), en.getValue());
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 用户是否有兴趣画像（冷启动判定）。 */
    public boolean hasInterest(String userId) {
        if (userId == null) {
            return false;
        }
        try {
            Long size = redisTemplate.opsForHash().size(INTEREST_PREFIX + userId);
            return size != null && size > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
