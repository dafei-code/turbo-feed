package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.moderation.SensitiveWord;
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
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
 * 敏感词管理后台（运营 / 管理员维护词库）。
 *
 * <p><b>权限（RBAC）</b>：{@code /api/admin/**} 由 {@link com.turbofeed.gateway.security.PermissionInterceptor}
 * 拦截；方法级 {@link RequirePermission} 声明所需权限为 {@link Permission#SYSTEM_CONFIG}，
 * 仅有 ADMIN 角色持有该权限——审核员(REVIEWER)与普通用户(USER)一律 {@code 40301}。</p>
 *
 * <p><b>热更新</b>：增/删/启停都会在 controller 内立即触发 {@link SensitiveWordService#reload()}
 * 重建 AC 自动机，最坏情况下后台请求 +1ms（词库 < 10k 时全量重建 < 1ms）。下次定时刷新
 * （30s）即便没人为触发，{@code (COUNT, MAX(updated_at))} 指纹变化也会重建——双保险。</p>
 *
 * <p><b>为什么不用 {@code /words/{id} 路径变量</b>：保持与
 * {@link AdminMediaController} 一致的 query 风格（admin 模块已习惯 query 传参），
 * 减少前端调用时的格式顾虑。</p>
 */
@RestController
@RequestMapping("/api/admin/moderation/words")
@RequiredArgsConstructor
public class AdminSensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    /**
     * 列出敏感词（分页，含禁用）。返回列表 + 当前 AC 已加载条数 + DB 总条数。
     */
    @GetMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(0, page) * limit;
        List<SensitiveWord> rows = sensitiveWordService.list(limit, offset);
        return Result.ok(Map.of(
                "items", rows,
                "dbCount", sensitiveWordService.count(),
                "acLoadedSize", sensitiveWordService.currentSize()));
    }

    /**
     * 新增 / 更新敏感词（按 word 唯一键 upsert）。
     */
    @PostMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<SensitiveWord> add(
            @RequestParam("word") String word,
            @RequestParam(value = "category", defaultValue = "DEFAULT") String category) {
        return Result.ok(sensitiveWordService.add(word, category));
    }

    /** 禁用（软删除，保留审计）。 */
    @PostMapping("/disable")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> disable(@RequestParam("id") long id) {
        sensitiveWordService.disable(id);
        return Result.ok();
    }

    /** 启用。 */
    @PostMapping("/enable")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> enable(@RequestParam("id") long id) {
        sensitiveWordService.enable(id);
        return Result.ok();
    }

    /** 硬删除（不可恢复；运营误操作风险，仅 ADMIN）。 */
    @PostMapping("/delete")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> delete(@RequestParam("id") long id) {
        sensitiveWordService.delete(id);
        return Result.ok();
    }

    /**
     * 强制刷新 AC 自动机（不等 30s 定时）。词库已变更但怀疑节点没生效时用。
     */
    @PostMapping("/reload")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Integer> reload() {
        return Result.ok(sensitiveWordService.reload());
    }
}
