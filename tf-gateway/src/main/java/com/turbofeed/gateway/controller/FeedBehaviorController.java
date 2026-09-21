package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行为埋点上报接口（抖音式流量池赛马的数据入口）。
 *
 * <p>前端在内容<b>曝光</b>（渲染进视口）、<b>完播</b>、<b>点赞</b>、<b>评论</b>、<b>分享</b>、
 * <b>不感兴趣</b>时调用本接口。引擎据此实时累加每帖的互动分，晋级器按互动率把内容从低池
 * 搬到高池放大曝光。</p>
 *
 * <p><b>权限</b>：{@link Permission#FEED_INTERACT}，授予所有登录用户（{@code USER} 角色即具备）。
 * 失败 fail-open，绝不阻断浏览/互动主流程。</p>
 */
@RestController
@RequestMapping("/api/feed")
public class FeedBehaviorController {

    private final BehaviorEventPublisher publisher;

    public FeedBehaviorController(BehaviorEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 上报一批行为事件。
     *
     * @param reports 行为事件列表（{@code postId} + {@code type}）
     */
    @PostMapping("/behavior")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Void> report(@RequestBody List<BehaviorReport> reports) {
        publisher.report(reports);
        return Result.ok();
    }
}
