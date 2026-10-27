package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.client.DlqReplayRequest;
import com.turbofeed.gateway.client.DlqReplayResultView;
import com.turbofeed.gateway.client.DlqSummaryView;
import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信重放的管理接口（P1-PR02）。
 *
 * <p><b>权限</b>：{@link Permission#SYSTEM_CONFIG}——重放会让内容重新入流，属系统级运维动作，
 * 比内容审核更高一级。未声明 {@code @RequirePermission} 会被 {@link
 * com.turbofeed.gateway.security.PermissionInterceptor} 按 deny-by-default 拒绝。</p>
 *
 * <p><b>降级口径</b>：引擎 DLQ 功能未启用（默认关闭）或不可用时，接口不报错、返回空列表 /
 * 零结果，由调用方（运维）自行判断——绝不因"查不到死信"而把一次管理操作变成异常。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin/feed/timeline/dlq")
@RequiredArgsConstructor
public class AdminFeedTimelineDlqController {

    private final FeedEngineClient feedEngineClient;

    /** 列出当前死信（按 mediaId 定位卡住的内容）。 */
    @GetMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<List<DlqSummaryView>> listDlq() {
        return Result.ok(feedEngineClient.dlqList().orElseGet(List::of));
    }

    /**
     * 重放死信。
     *
     * @param req {@code mediaIds} 精确重放（与 {@code all} 二选一）；{@code all=true} 全量重放
     */
    @PostMapping("/replay")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<DlqReplayResultView> replayDlq(@RequestBody DlqReplayRequest req) {
        return Result.ok(feedEngineClient.dlqReplay(
                        req.mediaIds() == null ? java.util.Set.of() : req.mediaIds(), req.all())
                .orElseGet(() -> new DlqReplayResultView(0, 0, 0)));
    }
}
