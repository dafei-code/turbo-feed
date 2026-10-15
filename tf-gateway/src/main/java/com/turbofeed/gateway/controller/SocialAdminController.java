package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.social.FollowService;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交关系管理端点（管理员，仅 ADMIN 可调用）。
 *
 * <p>当前仅提供「关注图重建」：从 DB（user_follow / user_fans）重建某用户的 Redis 社交图
 * （tf:follow / tf:follower / tf:user:stat）。用于 Redis 故障/重启后恢复一致性。
 * 双写已保证 Redis 实时一致，本端点仅作兜底。</p>
 *
 * <p><b>权限</b>：{@link Permission#USER_MANAGE}（仅 ADMIN 持有；REVIEWER 无此权限，
 * 配合 /api/admin/** 的 deny-by-default 拦截，普通用户与审核员均被拒）。</p>
 */
@RestController
@RequestMapping("/api/admin/social")
public class SocialAdminController {

    private final FollowService followService;

    public SocialAdminController(FollowService followService) {
        this.followService = followService;
    }

    /** 从 DB 重建某用户的 Redis 社交图。fail-open 由 FollowService 内部保证。 */
    @PostMapping("/rebuild")
    @RequirePermission(Permission.USER_MANAGE)
    public Result<Void> rebuild(@RequestParam("userId") long userId) {
        if (userId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "userId 不合法");
        }
        UserContextHolder.requireAdmin();
        followService.rebuildUserGraph(userId);
        return Result.ok();
    }
}
