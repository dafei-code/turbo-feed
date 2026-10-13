package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.like.MediaLikeService;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频点赞接口（抖音式：per-user 去重 + 已赞态 + 计数）。
 *
 * <p><b>两个端点</b>：
 * <ol>
 *   <li>{@code POST /api/feed/media/like}：toggle 点赞/取消。请求体 {@code {postId, like}}，
 *       去重后只有「实际状态变化」才发一次引擎行为事件（接赛马），返回最新 {@code {liked, likeCount}}。</li>
 *   <li>{@code GET /api/feed/media/like/status}：查某内容的已赞态 + 点赞数（卡片红心/计数渲染用）。</li>
 * </ol>
 * </p>
 *
 * <p><b>权限</b>：{@link Permission#FEED_INTERACT}（所有登录用户具备）。fail-open：异常不阻断浏览。</p>
 */
@RestController
@RequestMapping("/api/feed/media")
public class MediaLikeController {

    private final MediaLikeService likeService;

    public MediaLikeController(MediaLikeService likeService) {
        this.likeService = likeService;
    }

    /**
     * 切换点赞状态（抖音式 toggle）。
     *
     * @param req {@code postId}=被赞内容身份（=时间线幂等键），{@code like}=true 赞 / false 取消
     */
    @PostMapping("/like")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<MediaLikeStatus> like(@RequestBody MediaLikeRequest req) {
        if (req == null || req.postId() == null || req.postId().isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        boolean liked = likeService.apply(req.postId(), userId, req.like());
        return Result.ok(new MediaLikeStatus(liked, likeService.likeCount(req.postId())));
    }

    /**
     * 查询某内容的已赞态 + 点赞数。
     *
     * @param postId 内容身份（=时间线幂等键）
     */
    @GetMapping("/like/status")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<MediaLikeStatus> status(@RequestParam("postId") String postId) {
        if (postId == null || postId.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(new MediaLikeStatus(likeService.isLiked(postId, userId), likeService.likeCount(postId)));
    }

    /** 点赞请求体。 */
    public record MediaLikeRequest(String postId, Boolean like) {
    }

    /** 点赞状态响应。 */
    public record MediaLikeStatus(boolean liked, long likeCount) {
    }
}
