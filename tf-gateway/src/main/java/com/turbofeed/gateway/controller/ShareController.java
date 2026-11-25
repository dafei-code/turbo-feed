package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.share.ShareException;
import com.turbofeed.gateway.service.share.ShareService;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享接口（生成 / 我的分享）。
 *
 * <p>短链跳转的 {@code GET /s/{code}} 在 {@link ShortLinkController}，刻意不挂 {@code /api/v1}：
 * 它是给人点的链接，不是业务 API。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/share")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * 创建短链。请求体 {@code {postId, friendIds?}}；返回 {@code {code, shortPath, shortUrl, snapshot, notifiedFriends}}。
     */
    @PostMapping("/create")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<ShareService.CreateResult> create(@RequestBody ShareCreateRequest req) {
        if (req == null || req.postId() == null || req.postId().isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        try {
            long sharerId = Long.parseLong(UserContextHolder.requireUserId());
            return Result.ok(shareService.create(sharerId, req.postId(), req.friendIds()));
        } catch (NumberFormatException e) {
            return Result.fail(ErrorCode.PARAM_ERROR, "用户身份不合法");
        } catch (BizException e) {
            // requireUserId() 抛的是 BizException(UNAUTHORIZED)：必须原样透传，不能兜成 500
            return Result.fail(e.getErrorCode(), e.getMessage());
        } catch (ShareException e) {
            return Result.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return Result.fail(ErrorCode.INTERNAL_ERROR, "分享创建失败");
        }
    }

    /** 我的分享列表（游标分页，nextCursor=0 表示无更多）。 */
    @GetMapping("/my")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<ShareService.MyShareList> my(@RequestParam(value = "cursor", defaultValue = "0") long cursor,
                                               @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            long userId = Long.parseLong(UserContextHolder.requireUserId());
            return Result.ok(shareService.myShares(userId, cursor, limit));
        } catch (NumberFormatException e) {
            return Result.fail(ErrorCode.PARAM_ERROR, "用户身份不合法");
        } catch (BizException e) {
            return Result.fail(e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            return Result.fail(ErrorCode.INTERNAL_ERROR, "分享列表查询失败");
        }
    }

    /** 创建请求体。friendIds 可选，服务端会逐个校验互关关系。 */
    public record ShareCreateRequest(String postId, List<Long> friendIds) {
    }
}
