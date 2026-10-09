package com.turbofeed.feedengine.ranking;

import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时特征聚合服务（抖音式「实时特征流」的引擎侧消费入口）。
 *
 * <p>把 session 级最近互动（{@link SessionSequenceService#recent}，带 recency 权重）
 * 与短期兴趣 burst（{@link InterestService#shortTermTags}，12h 半衰期）合并成一份
 * {@link UserRealtimeProfile}：<b>每页只算一次</b>，交给 {@code RankingModel} 统一消费——
 * 比"每条候选各查一遍序列"更高效，也更贴"在线特征 → 模型"的工业形态。</p>
 *
 * <p><b>为什么不复用 sessionMatch 的逐候选重叠计算</b>：{@code FeedTimelineStore#scoreOf} 里已有
 * sessionMatch（候选标签 ∩ session 标签的逐候选重叠），那是"target-attention 最简形态"、保留零回归；
 * 本服务的实时画像是其<b>聚合升级</b>——把 session 与短期层先融成一份页面级画像，
 * 排序时直接查表（O(1)），且能额外承载"整体活跃强度"这类跨候选特征。二者在模型里叠加、互补。</p>
 *
 * <p>fail-open：任一异常返回 {@link UserRealtimeProfile#EMPTY}（实时特征退化，不阻断浏览）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class RealtimeFeatureService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeFeatureService.class);

    private final SessionSequenceService sessionSequenceService;
    private final InterestService interestService;
    private final int sessionTopN;
    private final int interestTopN;

    public RealtimeFeatureService(SessionSequenceService sessionSequenceService,
                                  InterestService interestService,
                                  @Value("${turbofeed.feed.realtime.session-top:20}") int sessionTopN,
                                  @Value("${turbofeed.feed.realtime.interest-top:30}") int interestTopN) {
        this.sessionSequenceService = sessionSequenceService;
        this.interestService = interestService;
        this.sessionTopN = sessionTopN;
        this.interestTopN = interestTopN;
    }

    /**
     * 聚合用户实时特征画像。
     *
     * @param userId 用户（{@code null} → 空画像，冷启动）
     * @return 实时画像（空 = 无实时信号 / 异常）
     */
    public UserRealtimeProfile profileOf(String userId) {
        if (userId == null) {
            return UserRealtimeProfile.EMPTY;
        }
        try {
            Map<String, Double> affinity = new LinkedHashMap<>();
            // 1) session 级最近互动：标签 × recency 权重（越近的互动贡献越大）
            for (SessionSequenceService.SessionItem si : sessionSequenceService.recent(userId, sessionTopN)) {
                if (si.tags() == null) {
                    continue;
                }
                for (String tag : si.tags()) {
                    affinity.merge(tag, si.recencyWeight(), Double::sum);
                }
            }
            // 2) 短期兴趣 burst（12h 半衰期，已衰减后权重）
            for (Map.Entry<String, Double> e : interestService.shortTermTags(userId, interestTopN).entrySet()) {
                affinity.merge(e.getKey(), e.getValue(), Double::sum);
            }
            double intensity = 0.0;
            for (double v : affinity.values()) {
                intensity += Math.abs(v);
            }
            return new UserRealtimeProfile(affinity, intensity);
        } catch (Exception e) {
            log.warn("实时特征聚合失败（fail-open，空画像）: userId={}, {}", userId, e.getMessage());
            return UserRealtimeProfile.EMPTY;
        }
    }
}
