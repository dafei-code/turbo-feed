package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaReviewService;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员审核接口（真审核闸的执行点）。
 *
 * <p><b>为什么需要它</b>：{@code turbofeed.media.review.auto-pass=false}（默认）时，
 * 上传经 {@code MediaReviewService#handleUploaded} 后停在 PENDING，不对公域可见。本控制器
 * 提供「审核队列查询 + 通过/驳回」两个操作，把内容翻成 APPROVED / REJECTED 终态——
 * 即抖音级「机审 + 人审」中的人审入口（机审占位实现见 {@code AutoPassModeration}）。</p>
 *
 * <p><b>鉴权（RBAC 已落地）</b>：{@code /api/admin/**} 已由 {@link com.turbofeed.gateway.security.PermissionInterceptor}
 * （注册于 {@code WebConfig#addInterceptors}）在 preHandle 按 {@link RequirePermission} 注解做权限校验——
 * 审核员(REVIEWER，含 CONTENT_REVIEW/CONTENT_TAKEDOWN/DASHBOARD_VIEW)与系统管理员(ADMIN，全权限)可操作，
 * 普通用户(USER)或匿名请求一律 {@code 40301 / 40101}（由 {@code GlobalExceptionHandler} 翻译为 {@code Result}）。
 * 方法级注解声明具体所需权限，新增角色只需在 {@code Role} 映射权限，此处无需改动。</p>
 *
 * <p><b>mediaId 传参方式</b>：mediaId 形如 {@code media/{userId}/{uuid}.{ext}} 本身含斜杠，
 * 不能做路径变量（PathPattern 会把斜杠当段分隔符），一律走 query 参数原样携带。</p>
 */
@RestController
@RequestMapping("/api/admin/media")
@RequiredArgsConstructor
public class AdminMediaController {

    private final MediaReviewService reviewService;
    private final MediaJdbcRepository mediaRepository;

    /**
     * 人工审核队列：全平台 PENDING 内容（按受理时间倒序，分页）。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 单页条数（默认 20，≤0 兜底 20）
     * @return 待审核内容列表（mediaId / url / status / createdAt）
     */
    @GetMapping("/pending")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<List<MediaItem>> pending(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(0, page) * limit;
        List<MediaItem> items = mediaRepository.listPendingGlobal(limit, offset);
        return Result.ok(items);
    }

    /**
     * 管理员审核执行：通过 / 驳回（真审核闸的翻转点）。
     *
     * @param mediaId 内容唯一标识（取自审核队列的 mediaId 字段，含斜杠，走 query）
     * @param approve true=通过（→ APPROVED，进公域）/ false=驳回（→ REJECTED，不展示）
     * @return 审核后的终态
     */
    @PostMapping("/review")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<MediaStatus> review(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("approve") boolean approve) {
        MediaStatus status = reviewService.reviewByMediaId(mediaId, approve);
        return Result.ok(status);
    }

    /**
     * 管理员处理举报：确认违规→内容下架 + 作者扣分；驳回→内容保持。
     *
     * @param mediaId  内容标识（含斜杠，走 query）
     * @param confirmed true=确认违规（下架）/ false=举报不成立（驳回）
     */
    @PostMapping("/report-review")
    @RequirePermission(Permission.CONTENT_TAKEDOWN)
    public Result<MediaStatus> reportReview(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("confirmed") boolean confirmed) {
        reviewService.handleReport(mediaId, confirmed);
        return Result.ok(confirmed ? MediaStatus.TAKEN_DOWN : MediaStatus.APPROVED);
    }

    /**
     * 管理员处理申诉：翻案→恢复公域（按信用池）+ 作者信用加回；维持→维持下架态。
     *
     * @param mediaId 内容标识（含斜杠，走 query）
     * @param upheld   true=翻案（恢复）/ false=维持原拒绝/下架
     */
    @PostMapping("/appeal-review")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<MediaStatus> appealReview(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("upheld") boolean upheld) {
        reviewService.handleAppeal(mediaId, upheld);
        return Result.ok(upheld ? MediaStatus.APPROVED : MediaStatus.TAKEN_DOWN);
    }
}
