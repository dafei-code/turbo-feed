package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
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
 * 通知中心接口（抖音式，ADR-0009）。
 *
 * <p><b>端点</b>：
 * <ol>
 *   <li>{@code GET /api/v1/notify/inbox}：收件箱列表（游标分页），返回 {@code {items, nextCursor, unreadCount}}。</li>
 *   <li>{@code GET /api/v1/notify/unread}：未读角标数（Redis 快路径，DB 回源）。</li>
 *   <li>{@code POST /api/v1/notify/read}：标记已读。请求体 {@code {ids?, type?}}；ids 优先，否则 type（ALL=全部）。</li>
 * </ol>
 * </p>
 *
 * <p><b>权限</b>：{@link Permission#FEED_INTERACT}（所有登录用户）。fail-open：异常不阻断浏览。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/notify")
public class NotificationController {

    private final NotificationGenerator notificationGenerator;

    public NotificationController(NotificationGenerator notificationGenerator) {
        this.notificationGenerator = notificationGenerator;
    }

    /**
     * 收件箱列表（游标分页）。
     *
     * @param cursor 游标（上一次返回的 nextCursor，首页传 0 或不传）
     * @param limit  每页条数（默认 20，1~100）
     */
    @GetMapping("/inbox")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<NotificationGenerator.NotificationListView> inbox(
            @RequestParam(value = "cursor", defaultValue = "0") long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(notificationGenerator.inbox(userId, cursor, limit));
    }

    /** 未读角标数。 */
    @GetMapping("/unread")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Long> unread() {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        return Result.ok(notificationGenerator.unreadCount(userId));
    }

    /**
     * 标记已读。
     *
     * @param req {@code ids}=指定通知 id 列表；{@code type}=按类型全清（{@code "ALL"} 或省略=全部）
     */
    @PostMapping("/read")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Long> read(@RequestBody NotificationReadRequest req) {
        long userId = Long.parseLong(UserContextHolder.requireUserId());
        List<Long> ids = (req == null || req.ids() == null) ? List.of() : req.ids();
        String type = (req == null) ? null : req.type();
        long remaining = notificationGenerator.markRead(userId, ids, type);
        return Result.ok(remaining);
    }

    /** 标记已读请求体。 */
    public record NotificationReadRequest(List<Long> ids, String type) {
    }
}
