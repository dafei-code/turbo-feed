package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.moderation.WhitelistEntry;
import com.turbofeed.gateway.service.moderation.WhitelistService;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 白名单管理后台（误杀治理出口）。
 *
 * <p><b>权限（RBAC）</b>：{@code /api/v1/admin/**} 由 {@link com.turbofeed.gateway.security.PermissionInterceptor}
 * 拦截；方法级 {@link RequirePermission} 声明所需权限为 {@link Permission#SYSTEM_CONFIG}，
 * 仅有 ADMIN 角色持有该权限——审核员(REVIEWER)与普通用户(USER)一律 {@code 40301}。
 * 与 {@link AdminSensitiveWordController} 完全一致。</p>
 *
 * <p><b>为什么需要白名单</b>：任何词库都必然误杀（如「黄色」「黄焖鸡」「水乳交融」），
 * 白名单把「该词是否敏感」从<b>词的属性</b>改成<b>词 × 场景 × 主体</b>的属性，
 * 给运营一个安全的自助豁免出口（只做减法，不会造成新的误杀）。后端
 * {@link WhitelistService} 已完整实现并热加载，本控制器仅把能力暴露给管理后台。</p>
 *
 * <p><b>热更新</b>：增 / 删 / 启停只写 DB 并触发后台异步重建（{@link WhitelistService#reload()}），
 * HTTP 立即返回，毫秒级生效（同词库控制器的单飞后台重建模型）。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin/moderation/whitelist")
@RequiredArgsConstructor
public class AdminWhitelistController {

    private final WhitelistService whitelistService;

    /**
     * 列出白名单条目（分页，含禁用）。返回列表 + DB 总条数 + 内存已加载条数。
     */
    @GetMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(0, page) * limit;
        return Result.ok(Map.of(
                "items", whitelistService.list(limit, offset),
                "dbCount", whitelistService.count(),
                "loadedSize", whitelistService.currentSize()));
    }

    /**
     * 新增 / 更新豁免条目。
     *
     * @param word    被豁免的词（必填）
     * @param scene   生效场景；空 → {@code *}（全部场景）。可用：* / NICKNAME / CAPTION / COMMENT
     * @param scope   生效范围；空 → {@code GLOBAL}（全局豁免）。可用：GLOBAL / USER
     * @param ownerId {@code scope=USER} 时的用户 ID；{@code GLOBAL} 时忽略
     * @param reason  豁免原因（建议填写，便于回溯）
     */
    @PostMapping
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<WhitelistEntry> add(
            @RequestParam("word") String word,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "ownerId", defaultValue = "0") long ownerId,
            @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(whitelistService.add(word, scene, scope, ownerId, reason));
    }

    /** 禁用（软删除，保留审计）。 */
    @PostMapping("/disable")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> disable(@RequestParam("id") long id) {
        whitelistService.disable(id);
        return Result.ok();
    }

    /** 启用。 */
    @PostMapping("/enable")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> enable(@RequestParam("id") long id) {
        whitelistService.enable(id);
        return Result.ok();
    }

    /** 硬删除（不可恢复；仅 ADMIN）。 */
    @PostMapping("/delete")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> delete(@RequestParam("id") long id) {
        whitelistService.delete(id);
        return Result.ok();
    }

    /** 强制刷新内存索引（不等 30s 定时）。 */
    @PostMapping("/reload")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Integer> reload() {
        return Result.ok(whitelistService.reload());
    }
}
