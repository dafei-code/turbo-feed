package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.share.ShareService;
import com.turbofeed.gateway.web.ApiVersion;
import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链运营盘点 / 失效管理（方案 P1-1）。
 *
 * <p><b>鉴权</b>：{@code /api/v1/admin/**} 由 PermissionInterceptor 按注解鉴权，
 * 且<b>缺省拒绝</b>——每个端点必须显式声明 {@code @RequirePermission}。本类用
 * {@link Permission#SYSTEM_CONFIG}（ADMIN 持有），盘点/作废均属系统配置范畴。</p>
 *
 * <p><b>分层</b>：查询与作废全部经 {@link ShareService} 收敛，Controller 不直连 Repository
 * （架构门禁零例外，事务边界/缓存失效只在 Service 写一遍）。</p>
 *
 * <p><b>查询代价</b>：按 code 游标分页但不带分片键，会广播 128 片；低频运营操作的已知代价，
 * 不为它改变 code 分片键（跳转主链路的长期承诺）。</p>
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/admin/share")
public class AdminShareController {

    private final ShareService shareService;

    public AdminShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * 盘点短链：按状态 / 分享者过滤，code 字典序游标分页（nextCursor 为 null 表示无更多）。
     *
     * @param status   可选：1=有效，0=已作废
     * @param sharerId 可选：按分享者过滤
     * @param cursor   可选：上一页最后一个 code
     * @param limit    每页条数（1-100，默认 20）
     */
    @GetMapping("/list")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<ShareService.AdminShareList> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "sharerId", required = false) Long sharerId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.ok(shareService.listForAdmin(status, sharerId, cursor, limit));
    }

    /**
     * 作废单条短链：DB 状态置失效 + 清 L1/L2 缓存并广播。幂等（重复调用返回相同结果）。
     *
     * <p>BF 位图不删位：已删除 code 仍判"可能存在"，继续查库并由状态校验返回不可用页，
     * 正确性不受影响（方案 §4.8.5）。</p>
     */
    @PostMapping("/{code}/invalidate")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Boolean> invalidate(@PathVariable("code") String code) {
        if (code == null || code.isBlank()) {
            return Result.fail(ErrorCode.PARAM_ERROR, "code 不能为空");
        }
        return Result.ok(shareService.invalidateByCode(code));
    }
}
