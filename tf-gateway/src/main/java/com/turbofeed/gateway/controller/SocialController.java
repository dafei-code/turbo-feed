package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.social.FollowService;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 社交关系接口（抖音式关注关系 P0-2）。
 *
 * <p><b>端点</b>：
 * <ol>
 *   <li>{@code POST /api/v1/social/follow?authorId=}：关注（幂等，重复关注返回 false）。</li>
 *   <li>{@code DELETE /api/v1/social/follow?authorId=}：取消关注（幂等）。</li>
 *   <li>{@code GET /api/v1/social/following?cursor=&limit=}：我的关注列表（抖音式游标分页）。</li>
 *   <li>{@code GET /api/v1/social/followers?cursor=&limit=}：我的粉丝列表。</li>
 *   <li>{@code GET /api/v1/social/relation?targetAuthorId=}：与某作者的关系（关注/粉丝/互关）。</li>
 *   <li>{@code GET /api/v1/social/recommend?limit=}：关注推荐（二阶关注）。</li>
 * </ol>
 * </p>
 *
 * <p><b>权限</b>：{@link Permission#FEED_INTERACT}（所有登录用户具备）。fail-open：异常不阻断。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/social")
public class SocialController {

    private final FollowService followService;

    public SocialController(FollowService followService) {
        this.followService = followService;
    }

    /** 关注某作者。返回 true=新关注 / false=原本已关注（幂等）。 */
    @PostMapping("/follow")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Boolean> follow(@RequestParam("authorId") long authorId) {
        if (authorId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "authorId 不合法");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(followService.follow(userId, authorId));
    }

    /** 取消关注某作者。返回 true=确有取关 / false=原本未关注（幂等）。 */
    @DeleteMapping("/follow")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Boolean> unfollow(@RequestParam("authorId") long authorId) {
        if (authorId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "authorId 不合法");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(followService.unfollow(userId, authorId));
    }

    /** 我的关注列表（游标分页）。 */
    @GetMapping("/following")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<FollowPageView> following(@RequestParam(value = "cursor", defaultValue = "0") long cursor,
                                            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        FollowService.FollowPage page = followService.following(userId, cursor, limit);
        return Result.ok(new FollowPageView(page.items(), page.nextCursor(),
                followService.stat(userId, "following")));
    }

    /** 我的粉丝列表（游标分页）。 */
    @GetMapping("/followers")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<FollowPageView> followers(@RequestParam(value = "cursor", defaultValue = "0") long cursor,
                                            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        FollowService.FollowPage page = followService.followers(userId, cursor, limit);
        return Result.ok(new FollowPageView(page.items(), page.nextCursor(),
                followService.stat(userId, "followers")));
    }

    /** 与某作者的关系（关注 / 粉丝 / 互关）。 */
    @GetMapping("/relation")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<RelationView> relation(@RequestParam("targetAuthorId") long targetAuthorId) {
        if (targetAuthorId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "targetAuthorId 不合法");
        }
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        boolean following = followService.isFollowing(userId, targetAuthorId);
        boolean follower = followService.isFollower(userId, targetAuthorId);
        return Result.ok(new RelationView(following, follower, following && follower));
    }

    /** 关注推荐（二阶关注）。 */
    @GetMapping("/recommend")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<List<String>> recommend(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(followService.recommend(userId, limit));
    }

    /** 关注/粉丝列表视图（items=userId 字符串，total=总计数）。 */
    public record FollowPageView(List<String> items, long nextCursor, long total) {
    }

    /** 关系视图。 */
    public record RelationView(boolean following, boolean follower, boolean mutual) {
    }
}
