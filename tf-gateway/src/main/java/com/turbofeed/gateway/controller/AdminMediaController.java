package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
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
 * <p><b>鉴权（TODO）</b>：当前与业务接口一致——仅当携带有效令牌时绑定操作人，未强制管理员角色。
 * 生产须在此叠加管理员角色校验（{@code @PreAuthorize} 或网关层），否则任意登录用户都能审核。</p>
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
    public Result<MediaStatus> review(
            @RequestParam("mediaId") String mediaId,
            @RequestParam("approve") boolean approve) {
        MediaStatus status = reviewService.reviewByMediaId(mediaId, approve);
        return Result.ok(status);
    }
}
