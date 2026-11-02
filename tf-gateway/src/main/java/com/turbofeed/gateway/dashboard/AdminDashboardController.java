package com.turbofeed.gateway.dashboard;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台总览接口（单端点聚合，避免前端并发打多个子系统）。
 *
 * <p>权限取 {@link Permission#DASHBOARD_VIEW}（ADMIN 角色默认包含）。内部再经
 * {@link DashboardService} 聚合引擎 / 计数服务 / 各组件健康度，全程 fail-open。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin")
@Slf4j
public class AdminDashboardController {

    private final DashboardService dashboardService;

    public AdminDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 管理后台总览数据：流量趋势（近 60 分钟读/写 QPS + 写 P99 + 近似 DAU）、
     * 热点榜 Top5、计数分桶负载、核心组件状态。
     *
     * <p>兜底：任何聚合/序列化异常都转为干净的 {@code Result}（而非 Spring 默认 500），
     * 并打 ERROR 日志保留真实堆栈——看板是只读监控端点，绝不应以 500 炸前端轮询。</p>
     */
    @GetMapping("/dashboard")
    @RequirePermission(Permission.DASHBOARD_VIEW)
    public Result<DashboardService.DashboardView> dashboard() {
        try {
            return Result.ok(dashboardService.build());
        } catch (Throwable t) {
            log.error("看板聚合失败（返回 INTERNAL_ERROR，不抛默认 500）", t);
            return Result.fail(ErrorCode.INTERNAL_ERROR, "看板数据聚合失败: " + t.getMessage());
        }
    }
}
