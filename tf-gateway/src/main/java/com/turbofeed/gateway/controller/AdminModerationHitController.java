package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.moderation.ModerationHit;
import com.turbofeed.gateway.service.moderation.ModerationHitRepository;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内容安全命中审计后台（人审复核 / 误杀漏放闭环）。
 *
 * <p>数据来自 {@code content_moderation_hit}：每次 BLOCK/REVIEW/LIMIT 命中自动落一行。
 * 审核员在此复核：
 * <ul>
 *   <li>判 <b>误杀（REJECTED）</b> → 该命中词进入误杀候选，运营可在白名单后台一键豁免；</li>
 *   <li>判 <b>确认违规（CONFIRMED）</b> → 留档，作为坏样本/词库升级依据。</li>
 * </ul>
 *
 * <p><b>权限</b>：列表与裁决均需 {@link Permission#CONTENT_REVIEW}（审核员/管理员），
 * 区别于词库维护的 SYSTEM_CONFIG——词库改词是配置动作，复核命中是审核动作。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin/moderation/hits")
@RequiredArgsConstructor
public class AdminModerationHitController {

    private static final Logger log = LoggerFactory.getLogger(AdminModerationHitController.class);

    private final ModerationHitRepository hitRepository;

    /** 命中列表（分页）。默认按 id 倒序，可按动作 / 人审结论过滤。 */
    @GetMapping
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "reviewerResult", required = false) String reviewerResult) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(0, page) * limit;
        List<ModerationHit> items = hitRepository.list(action, reviewerResult, limit, offset);
        return Result.ok(Map.of("items", items, "total", hitRepository.count()));
    }

    /**
     * 人审裁决一条命中。
     *
     * @param id      命中行 ID
     * @param result  CONFIRMED（确认违规）/ REJECTED（误杀）
     */
    @PostMapping("/verdict")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Void> verdict(@RequestParam("id") long id,
                                @RequestParam("result") String result) {
        if (!ModerationHit.REVIEWER_CONFIRMED.equals(result)
                && !ModerationHit.REVIEWER_REJECTED.equals(result)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "result 必须是 CONFIRMED 或 REJECTED");
        }
        hitRepository.updateReviewerResult(id, result);
        log.info("命中审计人审裁决: id={}, result={}", id, result);
        return Result.ok();
    }
}
