package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.review.MediaReviewService;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧治理接口：举报 / 申诉（抖音式闭环的群众参与端）。
 *
 * <p><b>mediaId 传参</b>：mediaId 形如 {@code media/{userId}/{uuid}.{ext}} 含斜杠，不能做路径变量，
 * 一律走 query 参数原样携带（与 {@code /api/media} 其它接口一致）。</p>
 *
 * <p><b>鉴权</b>：须有效 JWT（登录用户）；举报人/申诉人身份来自令牌，不可伪造。</p>
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaReportController {

    private final MediaReviewService reviewService;

    /**
     * 举报已发布内容（reason：涉黄/低俗/暴力/广告/侵权/其他 等）。
     * 高危类目由服务侧立即下架；普通举报进人工队列。
     */
    @PostMapping("/report")
    public Result<Void> report(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("reason") String reason) {
        long reporterUserId = Long.parseLong(UserContextHolder.requireUserId());
        reviewService.report(mediaId, reporterUserId, reason);
        return Result.ok();
    }

    /**
     * 作者申诉（仅自身被驳回/下架内容可申）。状态翻 APPEALING 暂不可见，进人工复核。
     */
    @PostMapping("/appeal")
    public Result<Void> appeal(@RequestParam("mediaId") String mediaId) {
        long authorUserId = Long.parseLong(UserContextHolder.requireUserId());
        reviewService.appeal(mediaId, authorUserId);
        return Result.ok();
    }
}
