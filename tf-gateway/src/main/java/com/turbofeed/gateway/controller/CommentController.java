package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.comment.Comment;
import com.turbofeed.gateway.service.comment.CommentService;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评论接口（独立模块，类抖音楼中楼 + 敏感词前置）。
 *
 * <p><b>URL 设计</b>：与 media 平行挂 {@code /api/comments/**}；{@code mediaId} 必传且
 * 走 query（mediaId 形如 {@code media/{uid}/{uuid}.{ext}} 含斜杠，不能做路径变量）。</p>
 *
 * <p><b>身份</b>：发布/点赞必须登录，userId 来自 {@code UserContextHolder}（JWT 解析后
 * 由 Filter 绑定 ThreadLocal，方法签名不出现身份参数，杜绝越权）。匿名读列表可放行
 * （公域可见性由 {@link com.turbofeed.gateway.service.query.MediaQueryService} 决定
 * 媒体是否 APPROVED，骨架阶段列表接口本身只做鉴权要求，审核过滤下沉到 repo 的 status 条件）。</p>
 *
 * <p><b>RBAC 接入点</b>：发布/点赞走 {@code /api/**} 默认拦截器（仅需登录）；
 * 后续如需「仅作者可点赞自己」等策略，在 controller 加业务校验即可。</p>
 */
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 发布评论（顶层或回复）。
     *
     * @param mediaId  被评论内容 ID
     * @param content  评论内容（≤1024 字符，走敏感词 fail-closed）
     * @param parentId 父评论 ID；缺省/0 = 顶层评论
     * @return 落库后的评论
     */
    @PostMapping
    public Result<Comment> publish(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("content") String content,
            @RequestParam(value = "parentId", defaultValue = "0") long parentId) {
        String userId = UserContextHolder.requireUserId();
        return Result.ok(commentService.publish(mediaId, Long.parseLong(userId), content, parentId));
    }

    /**
     * 顶层评论列表（{@code rootId=0}，按时间倒序，分页）。
     */
    @GetMapping
    public Result<List<Comment>> list(
            @RequestParam("mediaId") String mediaId,
            @RequestParam(value = "rootId", defaultValue = "0") long rootId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<Comment> data = (rootId == 0)
                ? commentService.listTopLevel(mediaId, page, size)
                : commentService.listReplies(mediaId, rootId, page, size);
        return Result.ok(data);
    }

    /**
     * 点赞（likeCount +1）。带 {@code mediaId} 走分片键路由。
     */
    @PostMapping("/like")
    public Result<Boolean> like(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("commentId") long commentId) {
        // 身份校验：未登录不能点赞
        UserContextHolder.requireUserId();
        return Result.ok(commentService.like(mediaId, commentId));
    }
}
