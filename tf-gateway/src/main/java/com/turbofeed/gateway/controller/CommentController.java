package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.comment.Comment;
import com.turbofeed.gateway.service.comment.CommentPage;
import com.turbofeed.gateway.service.comment.CommentService;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口（独立模块，类抖音楼中楼 + 敏感词前置）。
 *
 * <p><b>URL 设计</b>：与 media 平行挂 {@code /api/comments/**}；{@code mediaId} 必传且
 * 走 query（mediaId 形如 {@code media/{uid}/{uuid}.{ext}} 含斜杠，不能做路径变量）。</p>
 *
 * <p><b>分页 = keyset 游标</b>：列表用 {@code cursor}（上一页返回的 {@link CommentPage#nextCursor()}）
 * + {@code size}，替代 OFFSET 深分页；{@code mode=newest|hot} 切换「最新 / 最热」。匿名可读。</p>
 *
 * <p><b>身份</b>：发布/点赞必须登录，userId 来自 {@code UserContextHolder}（JWT 解析后
 * 由 Filter 绑定 ThreadLocal，方法签名不出现身份参数，杜绝越权）。</p>
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
     * @param content  评论内容（≤场景上限，走敏感词 fail-closed）
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
     * 评论列表（keyset 游标分页）。
     *
     * @param mediaId 被评论内容 ID
     * @param rootId  根评论 ID；0 = 顶层评论列表
     * @param size    本页大小（默认 20 / 顶层，50 / 回复）
     * @param cursor  上一页游标（首页留空）
     * @param mode    顶层列表排序：{@code newest}（默认）/ {@code hot}
     */
    @GetMapping
    public Result<CommentPage> list(
            @RequestParam("mediaId") String mediaId,
            @RequestParam(value = "rootId", defaultValue = "0") long rootId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "cursor", defaultValue = "") String cursor,
            @RequestParam(value = "mode", defaultValue = "newest") String mode) {
        CommentPage page = (rootId == 0)
                ? commentService.listTopLevel(mediaId, size, cursor, mode)
                : commentService.listReplies(mediaId, rootId, size, cursor);
        return Result.ok(page);
    }

    /**
     * 点赞 / 取消赞（抖音式 toggle）。带 {@code mediaId} 走分片键路由。
     *
     * @return 操作后的「是否已赞」态
     */
    @PostMapping("/like")
    public Result<Boolean> like(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("commentId") long commentId) {
        // 身份校验：未登录不能点赞
        String userId = UserContextHolder.requireUserId();
        return Result.ok(commentService.like(mediaId, commentId, Long.parseLong(userId)));
    }
}
