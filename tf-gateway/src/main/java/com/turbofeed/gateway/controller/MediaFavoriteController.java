package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.like.MediaFavoriteService;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频收藏接口（抖音式：per-user 去重 + 已收藏态 + 计数 + 我的收藏列表）。
 *
 * <p><b>三个端点</b>：
 * <ol>
 *   <li>{@code POST /api/v1/feed/media/favorite}：toggle 收藏/取消。请求体 {@code {postId, favorite}}，
 *       去重后只有「实际状态变化」才发一次引擎行为事件（接赛马 + 用户画像），返回最新 {@code {favorited, favoriteCount}}。</li>
 *   <li>{@code GET /api/v1/feed/media/favorite/status}：查某内容的已收藏态 + 收藏数（卡片收藏按钮/计数渲染用）。</li>
 *   <li>{@code GET /api/v1/feed/media/favorites}：查「我的收藏」列表（per-user ZSET 游标分页），返回 {@code {postIds, nextCursor}}。</li>
 * </ol>
 * </p>
 *
 * <p><b>权限</b>：{@link Permission#FEED_INTERACT}（所有登录用户具备）。fail-open：异常不阻断浏览。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/feed/media")
public class MediaFavoriteController {

    private final MediaFavoriteService favoriteService;

    public MediaFavoriteController(MediaFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * 切换收藏状态（抖音式 toggle）。
     *
     * @param req {@code postId}=被收藏内容身份（=时间线幂等键），{@code favorite}=true 收藏 / false 取消
     */
    @PostMapping("/favorite")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<MediaFavoriteStatus> favorite(@RequestBody MediaFavoriteRequest req) {
        if (req == null || req.postId() == null || req.postId().isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        boolean favorited = favoriteService.apply(req.postId(), userId, req.favorite());
        return Result.ok(new MediaFavoriteStatus(favorited, favoriteService.favoriteCount(req.postId())));
    }

    /**
     * 查询某内容的已收藏态 + 收藏数。
     *
     * @param postId 内容身份（=时间线幂等键）
     */
    @GetMapping("/favorite/status")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<MediaFavoriteStatus> status(@RequestParam("postId") String postId) {
        if (postId == null || postId.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(new MediaFavoriteStatus(favoriteService.isFavorited(postId, userId), favoriteService.favoriteCount(postId)));
    }

    /**
     * 我的收藏列表（游标分页）。
     *
     * @param cursor 游标（上一次返回的 nextCursor，首页传 0 或不传）
     * @param limit  每页条数（默认 20，范围 1~100）
     */
    @GetMapping("/favorites")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<MediaFavoriteList> favorites(@RequestParam(value = "cursor", defaultValue = "0") long cursor,
                                               @RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        MediaFavoriteService.FavoriteList list = favoriteService.myFavorites(userId, cursor, limit);
        return Result.ok(new MediaFavoriteList(list.postIds(), list.nextCursor()));
    }

    /** 收藏请求体。 */
    public record MediaFavoriteRequest(String postId, Boolean favorite) {
    }

    /** 收藏状态响应。 */
    public record MediaFavoriteStatus(boolean favorited, long favoriteCount) {
    }

    /** 我的收藏列表响应。 */
    public record MediaFavoriteList(java.util.List<String> postIds, long nextCursor) {
    }
}
