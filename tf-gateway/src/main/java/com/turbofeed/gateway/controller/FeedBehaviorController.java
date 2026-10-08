package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.gateway.service.review.MediaReviewService;
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
 *
 * <p><b>热度复审旁路（changelog 0066 抖音式「越火审得越严」）</b>：对批内<b>正向互动</b>
 * （{@code LIKE/COMMENT/SHARE}）逐条触发 {@link MediaReviewService#onInteraction}，由审核服务
 * 在 Redis 累计热度、达阈值时自动建 {@code HEAT_ACCUMULATED} 复审任务。该调用内部 fail-open，
 * 任何异常都不影响本接口的发布主流程。</p>
 */
@RestController
@RequestMapping("/api/feed")
public class FeedBehaviorController {

    private final BehaviorEventPublisher publisher;
    private final MediaReviewService reviewService;

    public FeedBehaviorController(BehaviorEventPublisher publisher, MediaReviewService reviewService) {
        this.publisher = publisher;
        this.reviewService = reviewService;
    }

    /**
     * 上报一批行为事件。
     *
     * @param reports 行为事件列表（{@code postId} + {@code type}）。
     *                M0 起客户端应一并带上 {@code requestId}（本次列表拉取的唯一 ID）
     *                与 {@code position}（该条目在本次结果里的位次）；二者缺省不影响
     *                统计与画像，但会让落盘明细无法用于训练（无法归组、无法校正位置偏差）。
     */
    @PostMapping("/behavior")
    @RequirePermission(Permission.FEED_INTERACT)
    public Result<Void> report(@RequestBody List<BehaviorReport> reports) {
        publisher.report(reports, UserContextHolder.requireUserId());
        // 热度复审旁路：逐条正向互动计入热度（fail-open，异常不阻断发布主流程）。
        if (reports != null) {
            for (BehaviorReport r : reports) {
                reviewService.onInteraction(r.postId(), r.type());
            }
        }
        return Result.ok();
    }
}
