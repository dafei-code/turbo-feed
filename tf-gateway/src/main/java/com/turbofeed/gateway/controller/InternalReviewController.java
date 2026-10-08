package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.review.MediaReviewService;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核域<b>内部接口</b>（服务间，{@code /internal/**}，非对外 API）。
 *
 * <p>路径约定与 feed-engine 的 {@code /internal/feed/**} 一致：统一挂在 {@code /internal/review} 下，
 * 便于网关/Ingress 层拒绝外部访问（依赖网络边界，非 RBAC）。</p>
 *
 * <p><b>非 RBAC 拦截</b>：{@code WebConfig} 仅对 {@code /api/admin/**} 注册
 * {@code PermissionInterceptor}，本接口在 {@code /internal/**} 下，不受角色拦截——
 * 与引擎内部接口（{@code PostStatController}）同源约定。调用方仅 feed-engine 的晋级回调。</p>
 *
 * <p><b>流量池分级（changelog 0067，抖音式「越火审得越严」）</b>：feed-engine 在内容晋级到更高池时
 * 回调 {@link #poolPromoted} 通知网关执行三件套加严（机审复扫 + 建 POOL_PROMOTED 复审任务 +
 * 按池级收紧热度阈值）。本接口 fail-open：处理异常由 {@code MediaReviewService#onPoolPromoted} 内部消化，
 * 不阻断引擎晋级主流程。</p>
 */
@RestController
@RequestMapping("/internal/review")
public class InternalReviewController {

    private final MediaReviewService reviewService;

    public InternalReviewController(MediaReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 流量池晋级加严回调（feed-engine → 网关）。
     *
     * @param postId 帖身份（与热度计数键同源）
     * @param from   晋级前所在池级（1/2/3）
     * @param to     晋级后所在池级（1/2/3，{@code to > from}）
     */
    @PostMapping("/pool-promoted")
    public Result<Void> poolPromoted(@RequestParam("postId") String postId,
                                     @RequestParam("from") int from,
                                     @RequestParam("to") int to) {
        reviewService.onPoolPromoted(postId, from, to);
        return Result.ok();
    }
}
