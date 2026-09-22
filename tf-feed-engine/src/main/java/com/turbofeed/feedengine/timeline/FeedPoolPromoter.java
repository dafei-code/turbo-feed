package com.turbofeed.feedengine.timeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 流量池晋级器（抖音式赛马的核心调度）。
 *
 * <p><b>为什么是"扫描 + 晋级"而不是"逐事件实时挪"</b>：行为事件量级大，每次都做
 * {@code ZREM+ZADD} 搬家成本高且易抖动；晋级本就是低频决策（互动率要攒够样本）。
 * 故采用<b>待评估集合 + 周期扫描</b>：{@link PostStatService} 在每次行为事件时把帖加入
 * {@code tf:feed:stat:tracked}，本类按 {@code fixedDelay} 扫这个有界集合，按互动率把内容
 * 从低池晋级到高池（仅升不降）。{@link #scanNow()} 同时供运维/测试手动触发，便于即时验证。</p>
 *
 * <p><b>晋级判定（阈值外置到 {@link FeedPoolPromoterProperties}，yml 可配，带默认值）</b>：
 * <ul>
 *   <li>样本不足（曝光 &lt; {@code minImpressions}）不动，避免小样本误判；</li>
 *   <li><b>差评降级（对称通道）</b>：差评率（dislikes/impressions）≥ {@code dislikeDemoteRatio}
 *       → 步降一级（{@code cur-1}，封底 L1）。与晋级每轮最多升一级对称，避免"一次差评直接清零"的剧烈抖动；
 *       持续差评会在后续多轮逐级降回 L1。这是 P1 赛马原先缺失的降级通道——原先只算降级目标却因
 *       {@code scanNow} 仅处理 {@code target > cur} 而被静默丢弃，差评内容仍停在高池放大。</li>
 *   <li>L1 且互动率 ≥ {@code p1ToP2Rate}（默认 5%）→ L2；L2 且互动率 ≥ {@code p2ToP3Rate}（默认 8%）→ L3（晋级）。</li>
 * </ul>
 * 互动率 = (点赞+评论+分享 + playCompleteWeight×完播) / 曝光。完播权重低于主动互动，符合短视频"看完≠喜欢"的直觉。</p>
 *
 * <p><b>晋级后即时失效推荐流缓存</b>：内容进更高池，读路径按权重立即给它更多占位，必须让
 * 缓存立刻反映——否则要等 15s TTL，用户体感不到"上热门"。</p>
 *
 * <p>fail-open：单帖晋级异常只告警，继续处理其余，不中断整轮扫描。</p>
 */
@Service
public class FeedPoolPromoter {

    private static final Logger log = LoggerFactory.getLogger(FeedPoolPromoter.class);

    private final PostStatService statService;
    private final FeedTimelineStore timelineStore;
    private final RecommendedFeedService recommendedFeedService;
    private final StringRedisTemplate redisTemplate;
    private final FeedPoolPromoterProperties props;

    public FeedPoolPromoter(PostStatService statService,
                            FeedTimelineStore timelineStore,
                            RecommendedFeedService recommendedFeedService,
                            StringRedisTemplate redisTemplate,
                            FeedPoolPromoterProperties props) {
        this.statService = statService;
        this.timelineStore = timelineStore;
        this.recommendedFeedService = recommendedFeedService;
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    /** 周期性扫描待评估集合，按互动率晋级（默认 30s，可配 {@code turbofeed.feed.promoter-interval-ms}）。 */
    @Scheduled(fixedDelayString = "${turbofeed.feed.promoter-interval-ms:30000}")
    public void promoteEligible() {
        scanNow();
    }

    /** 手动触发一轮晋级评估（运维/测试）。返回本轮晋级条数。 */
    public int scanNow() {
        Set<String> tracked;
        try {
            tracked = redisTemplate.opsForSet().members(PostStatService.TRACKED_KEY);
        } catch (Exception e) {
            log.warn("晋级扫描读取待评估集合失败: {}", e.getMessage());
            return 0;
        }
        if (tracked == null || tracked.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        int demoted = 0;
        for (String timelineKey : tracked) {
            if (timelineKey == null) {
                continue;
            }
            int cur = timelineStore.currentPool(timelineKey);
            if (cur <= 0) {
                // 已不在池中（过期/下架）→ 移出待评估集合，避免集合无限膨胀
                redisTemplate.opsForSet().remove(PostStatService.TRACKED_KEY, timelineKey);
                continue;
            }
            PostStatService.PostStat stat = statService.snapshot(timelineKey);
            int target = decideTargetPool(cur, stat);
            if (target > cur) {
                // 晋级（仅升不降）：进更高池 = 在推荐流里拿到更多占位
                if (timelineStore.promote(timelineKey, target)) {
                    promoted++;
                    recommendedFeedService.invalidate();
                    log.info("流量池晋级: timelineKey={}, {}→{} (impressions={}, rate={})",
                            timelineKey, cur, target, stat.impressions(), interactionRate(stat));
                }
                if (target >= FeedTimelineStore.MAX_POOL_LEVELS) {
                    redisTemplate.opsForSet().remove(PostStatService.TRACKED_KEY, timelineKey);
                }
            } else if (target < cur) {
                // 降级（对称通道）：差评率过高 → 步降一级（cur-1，封底 L1）。
                // 原先只计算降级目标却因 scanNow 仅处理 target>cur 而被丢弃，差评内容仍停在高池放大。
                if (timelineStore.demote(timelineKey, target)) {
                    demoted++;
                    recommendedFeedService.invalidate();
                    log.info("流量池降级: timelineKey={}, {}→{} (impressions={}, dislikeRatio={})",
                            timelineKey, cur, target, stat.impressions(),
                            stat.impressions() > 0 ? (double) stat.dislikes() / stat.impressions() : 0d);
                }
            }
        }
        if (promoted > 0) {
            log.info("流量池晋级本轮完成: 晋级 {} 条", promoted);
        }
        if (demoted > 0) {
            log.info("流量池降级本轮完成: 降级 {} 条", demoted);
        }
        return promoted + demoted;
    }

    private int decideTargetPool(int cur, PostStatService.PostStat s) {
        long imp = s.impressions();
        if (imp < props.getMinImpressions()) {
            return cur;                                   // 样本不足，暂不动
        }
        double dislikeRatio = imp > 0 ? (double) s.dislikes() / imp : 0d;
        if (dislikeRatio >= props.getDislikeDemoteRatio()) {
            // 差评率过高：步降一级（对称于晋级每轮最多升一级），封底 L1。
            // 原先此处写死返回 1，但 scanNow 只处理 target>cur，导致降级目标被静默丢弃、
            // 差评内容仍停在高池放大——这是 P1 赛马原先缺失的降级通道。
            return Math.max(1, cur - 1);
        }
        if (cur == 1 && interactionRate(s) >= props.getP1ToP2Rate()) {
            return 2;
        }
        if (cur == 2 && interactionRate(s) >= props.getP2ToP3Rate()) {
            return 3;
        }
        return cur;
    }

    private double interactionRate(PostStatService.PostStat s) {
        long imp = s.impressions();
        if (imp <= 0) {
            return 0d;
        }
        double interactions = s.likes() + s.comments() + s.shares() + props.getPlayCompleteWeight() * s.playCompletes();
        return interactions / imp;
    }
}
