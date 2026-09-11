package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 评论服务：发布 + 列表（顶层/楼中楼） + 点赞。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>敏感词前置</b>：与 media 描述共用 {@link SensitiveWordService#requireClean}，
 *       命中即 fail-closed 抛 {@link ErrorCode#SENSITIVE_WORD_HIT}。</li>
 *   <li><b>楼中楼 rootId 派生</b>：回复的 {@code rootId} = 父评论的根评论 ID；
 *       父为顶层时（{@code parent.rootId=0}）取父的 commentId 作为根。父查询带
 *       {@code media_id} 分片键单分片命中，不广播。</li>
 *   <li><b>分页</b>：顶层用 {@code OFFSET}（按时间倒序），回复同；深度翻页可演进
 *       为 {@code created_at < cursor} 的 keyset 分页。</li>
 *   <li><b>状态</b>：敏感词通过即 {@code APPROVED}（fail-closed 已在 requireClean 完成）。
 *       灰度词 → {@code PENDING} 留作后续「类抖音机器初审」扩展点。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentJdbcRepository commentRepository;
    private final SensitiveWordService sensitiveWordService;
    private final SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator();

    private static final int MAX_CONTENT_LEN = 1024;

    /**
     * 发布评论（顶层或回复）。
     *
     * @param mediaId  被评论内容 ID（分片键，必传）
     * @param userId   评论者（来自 JWT）
     * @param content  评论文本（≤1024 字符）
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
        if (content.length() > MAX_CONTENT_LEN) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评论内容过长（≤" + MAX_CONTENT_LEN + "）");
        }
        // 敏感词 fail-closed
        sensitiveWordService.requireClean(content);

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
        Comment c = new Comment(
                commentId, mediaId, userId, rootId, parentId, content,
                CommentStatus.APPROVED, 0, Instant.now());
        commentRepository.insert(c);
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
