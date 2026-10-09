package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.review.ReviewTask;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 评论服务：发布 + 列表（顶层/楼中楼） + 点赞。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>内容安全前置</b>：统一走 {@link ContentSecurityService#requireClean}
 *       （场景 {@link ContentScene#COMMENT}），一次调用同时完成长度上限与敏感词检测；
 *       命中即 fail-closed 抛 {@link ErrorCode#SENSITIVE_WORD_HIT}。
 *       <b>长度上限不再在本地声明</b>——上限归 {@code turbofeed.content-security.scene-max-length}
 *       单一事实源，本地再写一个 1024 只会在某次调整后与 DB 列宽/前端上限三方不一致。</li>
 *   <li><b>楼中楼 rootId 派生</b>：回复的 {@code rootId} = 父评论的根评论 ID；
 *       父为顶层时（{@code parent.rootId=0}）取父的 commentId 作为根。父查询带
 *       {@code media_id} 分片键单分片命中，不广播。</li>
 *   <li><b>分页</b>：顶层用 {@code OFFSET}（按时间倒序），回复同；深度翻页可演进
 *       为 {@code created_at < cursor} 的 keyset 分页。</li>
 *   <li><b>状态</b>：内容安全通过即 {@code APPROVED}。灰度词 → {@code PENDING}
 *       留作后续「机器初审 + 人审队列」扩展点（见
 *       {@code docs/architecture/content-security-design.md}）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private final CommentJdbcRepository commentRepository;
    private final ContentSecurityService contentSecurityService;
    /** 雪花 ID 生成器由 {@code SnowflakeConfig} 装配（workerId/datacenterId 必须逐实例区分）。 */
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    /** 评论区异常感知（P1 #133）：速率/聚集异常检测，fail-open。 */
    private final CommentRiskService commentRiskService;
    /** 复审任务仓储（进 COMMENT_ANOMALY 巡查队列，复用 review_task 单表）。 */
    private final ReviewTaskRepository reviewTaskRepository;
    /** 评论区风控配置（turbofeed.media.comment-risk.*）。 */
    private final MediaProperties properties;
    /** 异常行为日志（P2-2）：评论异常行为持久化，供 P2-1 信号闭环来源（fail-open）。 */
    private final BehaviorLogService behaviorLogService;

    /**
     * 发布评论（顶层或回复）。
     *
     * @param mediaId  被评论内容 ID（分片键，必传）
     * @param userId   评论者（来自 JWT）
     * @param content  评论文本（上限见 {@code turbofeed.content-security.scene-max-length.COMMENT}）
     * @param parentId 父评论 ID；0 = 顶层评论
     * @return 落库后的评论（含生成的 commentId）
     * @throws BizException PARAM_ERROR / SENSITIVE_WORD_HIT / NOT_FOUND
     */
    public Comment publish(String mediaId, long userId, String content, long parentId) {
        if (mediaId == null || mediaId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mediaId 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评论内容不能为空");
        }
        // 长度上限 + 敏感词（含归一化抗绕过与白名单豁免）
        contentSecurityService.requireClean(ContentScene.COMMENT, content, userId);

        // #133 评论区异常感知（速率/聚集）：先过风险检测再落库
        CommentRiskService.Assessment risk = commentRiskService.assess(mediaId, userId);
        if (risk.level() == CommentRiskService.RiskLevel.USER_THROTTLED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评论过于频繁，请稍后再试");
        }

        long rootId;
        if (parentId == 0) {
            rootId = 0; // 顶层
        } else {
            CommentJdbcRepository.ParentLocation parent = commentRepository.findParent(mediaId, parentId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "父评论不存在"));
            // 父为顶层 → 新回复的根 = 父的 commentId；父已是回复 → 沿用父的 rootId
            rootId = parent.rootId() == 0 ? parent.commentId() : parent.rootId();
        }

        long commentId = snowflakeIdGenerator.nextId();
        // #133：速率异常且开启折叠 → 该评论 FOLDED（前端默认不展示），否则 APPROVED
        boolean spikeFold = risk.level() == CommentRiskService.RiskLevel.CONTENT_SPIKE
                && properties.getCommentRisk().isFoldOnSpike();
        CommentStatus status = spikeFold ? CommentStatus.FOLDED : CommentStatus.APPROVED;
        Comment c = new Comment(
                commentId, mediaId, userId, rootId, parentId, content,
                status, 0, Instant.now());
        commentRepository.insert(c);
        // #133：评论区速率异常 → 进 REVIEWER 巡查队列（仅巡查，不下架内容），防重复建单
        if (spikeFold && !reviewTaskRepository.existsOpenForMedia(mediaId)) {
            reviewTaskRepository.insert(mediaId, userId, ReviewTask.TYPE_COMMENT_ANOMALY,
                    (int) Math.min(risk.rateCount(), Integer.MAX_VALUE));
            log.info("评论区速率异常→建巡查任务(COMMENT_ANOMALY，REVIEWER 二次研判): mediaId={}, rateCount={}", mediaId, risk.rateCount());
            // 异常行为日志（P2-2）：评论异常行为归评论者，供 P2-1 信号闭环来源（fail-open，不影响主流程）
            behaviorLogService.record(userId, mediaId, "COMMENT_ANOMALY", "rateCount=" + risk.rateCount());
        }
        return c;
    }

    /** 顶层评论列表（按时间倒序）。 */
    public List<Comment> listTopLevel(String mediaId, int page, int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(page, 0) * limit;
        return commentRepository.listTopLevel(mediaId, limit, offset);
    }

    /** 某根评论下的回复（按时间正序）。rootId 必须 > 0。 */
    public List<Comment> listReplies(String mediaId, long rootId, int page, int size) {
        if (rootId <= 0) {
            return List.of();
        }
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(page, 0) * limit;
        return commentRepository.listReplies(mediaId, rootId, limit, offset);
    }

    public long countTopLevel(String mediaId) {
        return commentRepository.countTopLevel(mediaId);
    }

    /**
     * 点赞 +1。
     *
     * <p>骨架阶段不做「同用户去重」（需 Redis Set 维护 {@code likedBy}），每次调用 +1；
     * 真实去重可在 controller 入口先 {@code SADD tf:comment:liked:{commentId} {userId}}，
     * 返回 0 则跳过。带 {@code media_id} 分片键路由。</p>
     */
    public boolean like(String mediaId, long commentId) {
        int rows = commentRepository.incrementLike(mediaId, commentId);
        return rows > 0;
    }
}
