package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.moderation.ModerationExemplar;
import com.turbofeed.gateway.service.moderation.ModerationExemplarRepository;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 坏样本库管理后台（语义层样本维护）。
 *
 * <p>运营把人审 CONFIRMED 的漏放表达补录进来，sidecar 加载后下次就能挡住同类改写。
 * 权限同命中审计：{@link Permission#CONTENT_REVIEW}（审核员/管理员）。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin/moderation/exemplars")
@RequiredArgsConstructor
public class AdminModerationExemplarController {

    private final ModerationExemplarRepository exemplarRepository;

    @GetMapping
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(0, page) * limit;
        List<ModerationExemplar> items = exemplarRepository.list(category, includeDisabled, limit, offset);
        return Result.ok(Map.of("items", items, "total", exemplarRepository.count()));
    }

    @PostMapping
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Void> add(
            @RequestParam("text") String text,
            @RequestParam(value = "category", defaultValue = "DEFAULT") String category,
            @RequestParam(value = "source", defaultValue = "MANUAL") String source) {
        exemplarRepository.insert(text, category, source);
        return Result.ok();
    }

    @PostMapping("/disable")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Void> disable(@RequestParam("id") long id) {
        exemplarRepository.disable(id);
        return Result.ok();
    }

    @PostMapping("/enable")
    @RequirePermission(Permission.CONTENT_REVIEW)
    public Result<Void> enable(@RequestParam("id") long id) {
        exemplarRepository.enable(id);
        return Result.ok();
    }
}
