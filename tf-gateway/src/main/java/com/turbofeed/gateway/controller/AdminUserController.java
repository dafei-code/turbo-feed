package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.AuthService;
import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理后台接口（RBAC 已落地，{@code /api/admin/**} 由 PermissionInterceptor 按注解鉴权）。
 *
 * <p>当前提供「角色调整」：改库 + 吊销该用户全部刷新令牌，使角色变更即时收敛
 * （见 {@link AuthService#changeRole}）。需 {@link Permission#USER_MANAGE} 权限（ADMIN 持有）。</p>
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final AuthService authService;

    /**
     * 调整用户角色，并吊销其全部刷新令牌（角色变更即时生效）。
     *
     * <p>吊销刷新令牌后，该用户各端无法再刷新，访问令牌在 ≤30min 内自然过期，
     * 重新登录即携带新角色——实现「升权 / 降权 / 封禁」即时收敛。
     * 访问令牌短期旧角色窗口属预期（短 TTL 兜底）。</p>
     *
     * @param id 目标用户 uid（user 表分片键，按 id 单分片命中）
     * @param role 目标角色编码：USER / REVIEWER / ADMIN
     * @return 吊销的刷新令牌数（data）
     */
    @PostMapping("/{id}/role")
    @RequirePermission(Permission.USER_MANAGE)
    public Result<Long> changeRole(@PathVariable("id") long id,
                                   @RequestParam("role") String role) {
        long revoked = authService.changeRole(String.valueOf(id), role);
        return Result.ok(revoked);
    }
}
