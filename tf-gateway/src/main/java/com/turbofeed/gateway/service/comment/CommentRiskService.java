package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 评论区异常感知（P1 #133 评论区风控：速率/聚集异常）。
 *
 * <p>在评论写入路径上做轻量异常感知（不依赖评论内容深度解析，与举报水位因子同构）：</p>
 * <ul>
 *   <li><b>聚集异常（单账号刷评）</b>：单账号对同一条内容的近窗评论频次超 {@code userLimit}
 *       → 直接限流该账号（拒评），防止水军批量刷评轰炸评论区；</li>
 *   <li><b>速率异常（评论区爆发）</b>：某内容近窗评论总数超 {@code rateThreshold}
 *       → 该条评论自动折叠（{@code CommentStatus#FOLDED}，前端默认不展示）+ 进 REVIEWER
 *       巡查队列（{@code ReviewTask#TYPE_COMMENT_ANOMALY}），由人工研判是否需治理评论区。</li>
 * </ul>
 *
 * <p><b>fail-open</b>：Redis 抖动仅记日志、按正常放行（{@code NORMAL}），绝不阻断评论主流程。</p>
 *
 * <p><b>与举报/内容审核解耦</b>：评论区异常治理对象是「评论区」而非「内容本身」——
 * 巡查任务处置仅标记已巡查、<b>不下架内容</b>（见 {@code MediaReviewService#decideReviewTask} 的
 * COMMENT_ANOMALY 早返回分支），避免把「评论区刷屏」误判成「内容违规下架」。</p>
 */
@Service
public class CommentRiskService {

    private static final Logger log = LoggerFactory.getLogger(CommentRiskService.class);

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties properties;

    public CommentRiskService(StringRedisTemplate redisTemplate, MediaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 异常等级。 */
    public enum RiskLevel {
        /** 正常，按 APPROVED 落库。 */
        NORMAL,
        /** 单账号对单内容近窗评论频次超上限 → 限流该账号（拒评）。 */
        USER_THROTTLED,
        /** 内容评论区近窗评论总数超阈值 → 折叠 + 进巡查队列。 */
        CONTENT_SPIKE
    }

    /** 感知结果：异常等级 + 触发时的内容评论速率计数（用于进复审任务的 triggerCount）。 */
    public record Assessment(RiskLevel level, long rateCount) {
    }

    /**
     * 评估一次评论发布的风险。两个窗口计数都 INCR（刷评也计入内容速率），
     * 返回风险等级与当前内容评论速率计数。
     */
    public Assessment assess(String mediaId, long userId) {
        MediaProperties.CommentRisk cfg = properties.getCommentRisk();
        try {
            // ① 单账号对单内容聚集度
            String userKey = "tf:comment:user:" + userId + ":" + mediaId;
            Long u = redisTemplate.opsForValue().increment(userKey);
            if (u != null && u == 1) {
                redisTemplate.expire(userKey, Duration.ofSeconds(cfg.getUserWindowSeconds()));
            }
            boolean userThrottled = u != null && u > cfg.getUserLimit();

            // ② 内容评论区速率
            String rateKey = "tf:comment:rate:" + mediaId;
            Long r = redisTemplate.opsForValue().increment(rateKey);
            if (r != null && r == 1) {
                redisTemplate.expire(rateKey, Duration.ofSeconds(cfg.getWindowSeconds()));
            }
            boolean spike = r != null && r > cfg.getRateThreshold();

            RiskLevel level = userThrottled ? RiskLevel.USER_THROTTLED
                    : (spike ? RiskLevel.CONTENT_SPIKE : RiskLevel.NORMAL);
            return new Assessment(level, r == null ? 0L : r);
        } catch (Exception e) {
            log.warn("评论异常感知失败（fail-open，按正常放行）: mediaId={}, userId={}, {}", mediaId, userId, e.getMessage());
            return new Assessment(RiskLevel.NORMAL, 0L);
        }
    }
}
