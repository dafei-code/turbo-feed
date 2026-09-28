package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>「不感兴趣」的专属负反馈通道</b>：{@code NOT_INTERESTED} <b>不进权重表</b>，而是把该内容的
 * 标签并入用户级负向标签集 {@code tf:user:dislike-tags:{userId}}（Redis SET），由读路径
 * （{@code FeedTimelineStore#readPage}）对该用户<b>打压同标签内容</b>——这是抖音式负反馈的核心体感：
 * 用户点一次"不感兴趣"，此后<b>所有带该标签的内容</b>（而不是他点过的那一条）在他的流里都会沉底。
 * 与 {@code DISLIKE} 明确分工：后者作用于<b>单条内容</b>（画像负权重 + 流量池全局降级），
 * 前者作用于<b>该用户的相似内容</b>；二者互不干扰，避免把两种语义混成一个信号。</p>
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
    /** 「不感兴趣」行为类型（负反馈专属通道）。 */
    private static final String NEGATIVE_TYPE = "NOT_INTERESTED";
    /**
     * 用户负向标签集 {@code tf:user:dislike-tags:{userId}}（Redis SET）。
     *
     * <p>与画像 Hash 分离的原因：画像用权重表达"多喜欢"，负反馈用<b>集合</b>表达"不要这个标签"，
     * 二者语义与消费方都不同（前者参与加成，后者触发沉底惩罚），混存会让负反馈被相似的兴趣
     * 权重抵消而失效——用户明明点过"不感兴趣"，仍可能因为同标签其它内容的高权重被捞回来。</p>
     */
    private static final String NEG_PREFIX = "tf:user:dislike-tags:";
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
     *
     * <p>{@code NOT_INTERESTED} 走 {@link #recordNegativeTags} 专属通道，不落到权重表里。</p>
     */
    public void accumulateFromEvent(FeedBehaviorEvent event) {
        if (event == null || event.userId() == null || event.timelineKey() == null) {
            return;
        }
        String type = event.type() == null ? "" : event.type().toUpperCase();
        if (NEGATIVE_TYPE.equals(type)) {
            // 抖音式负反馈：不是"给画像减分"，而是"以后别再给我推这类内容"
            recordNegativeTags(event.userId(), event.timelineKey());
            return;
        }
        double weight = WEIGHTS.getOrDefault(type, 0.0);
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
     * 记录「不感兴趣」负反馈：把目标内容的<b>全部标签</b>并入该用户的负向标签集。
     *
     * <p><b>为什么并入"全部标签"而不是只记被点的那条内容</b>：负反馈的语义是
     * "这类内容我不想看"，记标签才能泛化到其它同标签内容；只记 timelineKey 则只能过滤单条，
     * 用户会在同一类内容上反复点"不感兴趣"，体感极差（这正是抖音把它做成标签级 suppression 的原因）。</p>
     *
     * <p>幂等（SET 天然去重），且受同一份 {@link #TTL} 约束——长期不活跃的负反馈会自然过期，
     * 避免"三年前点过一次不感兴趣"永久影响推荐。</p>
     *
     * <p>fail-open：写入失败只告警，最多让本次负反馈不生效，不影响浏览。</p>
     */
    public void recordNegativeTags(String userId, String timelineKey) {
        Set<String> tags = tagIndexService.tagsOf(timelineKey);
        if (tags == null || tags.isEmpty()) {
            // 无标签内容：没有可泛化的维度，本次负反馈无从表达，静默忽略
            return;
        }
        try {
            String key = NEG_PREFIX + userId;
            redisTemplate.opsForSet().add(key, tags.toArray(new String[0]));
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InterestService.class)
                    .warn("负反馈标签写入失败（不影响主流程）: userId={}, timelineKey={}, {}",
                            userId, timelineKey, e.getMessage());
        }
    }

    /**
     * 取用户的负向标签集（读路径据此对相似内容应用沉底惩罚）。
     *
     * @param userId 用户（{@code null} → 空集，匿名/冷启动不做任何打压）
     * @return 负向标签集合；无/异常返回空集（fail-open，绝不影响可见性）
     */
    public Set<String> negativeTags(String userId) {
        if (userId == null) {
            return Set.of();
        }
        try {
            Set<String> tags = redisTemplate.opsForSet().members(NEG_PREFIX + userId);
            return tags == null ? Set.of() : tags;
        } catch (Exception e) {
            return Set.of();
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
                // StringRedisTemplate 的 Hash 值是 String（如 "2.7"），不能强转 Number（CCE→fail-open 吞掉→画像永远为空）
                double v = Double.parseDouble(String.valueOf(e.getValue()));
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
