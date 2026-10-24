package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.comment.CommentLikeService;
import com.turbofeed.gateway.service.like.MediaLikeService;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动计数运维接口（A4 闭环：把「Redis 丢了怎么办」从注释承诺变成可执行动作）。
 *
 * <p><b>为什么需要它</b>：点赞/评论点赞的权威计数在 Redis（64 槽去重集合 + 计数 Key），
 * {@code media_like} / {@code comment_like} 表<b>只为灾备重建而落库</b>。改造前全仓没有任何
 * 重建入口——Redis 数据丢失、计数漂移、TTL 误回收时，注释里的「灾备」无法执行。
 * 本控制器把 {@code MediaLikeService#rebuildFromDb} / {@code recountFromSlots} 与
 * {@code CommentLikeService} 的同名能力暴露为管理端动作。</p>
 *
 * <p><b>两档操作的代价差异</b>：</p>
 * <ul>
 *   <li>{@code recount}（对账）：<b>只读 Redis</b>，用 64 槽去重集合重算计数 Key。
 *       适用于「计数 Key 与去重集合漂移」——去重集合本身可信时的首选，成本低、不碰 DB。</li>
 *   <li>{@code rebuild}（灾备重建）：<b>查 DB</b> 并重建 Redis 去重集合 + 计数 Key。
 *       适用于「Redis 整体丢失 / 去重集合已不可信」。会按分片键查一次单分片，成本高于 recount。</li>
 * </ul>
 *
 * <p><b>幂等</b>：两者均可重复执行——{@code recount} 是覆盖写，{@code rebuild} 走 {@code SADD}
 * （天然去重）后覆盖计数 Key。重复触发不会把计数越推越高。</p>
 *
 * <p><b>权限</b>：{@link Permission#SYSTEM_CONFIG}（仅 ADMIN）。刻意<b>不用</b>
 * {@code CONTENT_REVIEW}——这是运维校正动作，不是内容审核动作；借用审核权限会让
 * REVIEWER 角色意外获得改写计数的能力。</p>
 *
 * <p><b>传参方式</b>：postId / mediaId 形如 {@code post/{userId}/{uuid}}、
 * {@code media/{userId}/{uuid}.{ext}}，本身含斜杠，不能做路径变量，一律走 query 参数原样携带
 * （与 {@code AdminMediaController} 同约定）。</p>
 */
@RestController
@RequestMapping("/api/admin/counter")
@RequiredArgsConstructor
public class AdminCounterRebuildController {

    private final MediaLikeService mediaLikeService;
    private final CommentLikeService commentLikeService;

    /**
     * 内容点赞计数对账（只读 Redis，按 64 槽去重集合重算计数 Key）。
     *
     * @param postId 内容身份（= 时间线幂等键，含斜杠，走 query）
     * @return 校正后的点赞数
     */
    @PostMapping("/media-like/recount")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Long> recountMediaLike(@RequestParam("postId") String postId) {
        if (postId == null || postId.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        return Result.ok(mediaLikeService.recountFromSlots(postId));
    }

    /**
     * 内容点赞灾备重建（查 {@code media_like} 表，重建 Redis 去重集合 + 计数 Key）。
     *
     * @param postId 内容身份（= 时间线幂等键，含斜杠，走 query）
     * @return 重建后的点赞数（= DB 中该内容的点赞行数）
     */
    @PostMapping("/media-like/rebuild")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Long> rebuildMediaLike(@RequestParam("postId") String postId) {
        if (postId == null || postId.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        return Result.ok(mediaLikeService.rebuildFromDb(postId));
    }

    /**
     * 评论点赞计数对账（只读 Redis，按 64 槽去重集合重算计数 Key）。
     *
     * @param commentId 被对账的评论 ID
     * @return 校正后的点赞数
     */
    @PostMapping("/comment-like/recount")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Long> recountCommentLike(@RequestParam("commentId") long commentId) {
        if (commentId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "commentId 非法");
        }
        return Result.ok(commentLikeService.recountFromSlots(String.valueOf(commentId)));
    }

    /**
     * 评论点赞灾备重建（查 {@code comment_like} 表，重建 Redis 去重集合 + 计数 Key）。
     *
     * @param mediaId   分片键（{@code comment_like} 按 media_id 分片，必须带上避免全分片广播）
     * @param commentId 被重建的评论 ID
     * @return 重建后的点赞数（= DB 中该评论的点赞行数）
     */
    @PostMapping("/comment-like/rebuild")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Long> rebuildCommentLike(@RequestParam("mediaId") String mediaId,
                                          @RequestParam("commentId") long commentId) {
        if (mediaId == null || mediaId.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "mediaId 不能为空");
        }
        if (commentId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "commentId 非法");
        }
        return Result.ok(commentLikeService.rebuildFromDb(mediaId, commentId));
    }
}
