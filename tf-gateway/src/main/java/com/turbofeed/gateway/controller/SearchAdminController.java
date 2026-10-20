package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.search.SearchRebuildService;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索索引运维接口（后台，需 {@link Permission#SYSTEM_CONFIG}，当前仅 ADMIN 持有）。
 *
 * <p><b>为什么必须挂在 {@code /api/admin/**} 而不是跟搜索接口放一起</b>：这里的每条重建都会
 * 对 128 张物理分片做<b>广播全表扫描</b>。它绝不能出现在用户请求路径上——一次误触发就是
 * 一次全库扫描。{@code /api/admin/**} 由 {@code PermissionInterceptor} 按注解鉴权，
 * 天然把外部请求挡在门外。</p>
 *
 * <p><b>默认 dryRun=true</b>：先看清会影响多少行，再决定是否落。重建是幂等的，
 * 但「知道自己在改什么」比「随手就跑」重要。</p>
 */
@RestController
@RequestMapping("/api/admin/search")
public class SearchAdminController {

    private final SearchRebuildService rebuildService;

    public SearchAdminController(SearchRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    /**
     * 重建搜索索引。
     *
     * @param scope   重建范围：
     *                <ul>
     *                  <li>{@code tag-lexicon} —— 话题联想词典（{@code media_tag} 去重标签）；</li>
     *                  <li>{@code user-index} —— 用户昵称索引 + 用户联想词（{@code user} 表）；</li>
     *                  <li>{@code tag-index} —— 标签有序倒排（让 7 天外的老内容重新可搜）；</li>
     *                  <li>{@code all} —— 三者依次执行。</li>
     *                </ul>
     * @param dryRun  只统计不写入（默认 {@code true}）
     * @param maxRows 单次最多处理的行数（默认 5000，防一次跑太久）
     * @return 各范围的重建报告；{@code all} 时返回第一条为汇总、其余按顺序排列的列表
     */
    @PostMapping("/rebuild")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<List<SearchRebuildService.RebuildReport>> rebuild(
            @RequestParam(value = "scope", defaultValue = "tag-lexicon") String scope,
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(value = "maxRows", defaultValue = "5000") int maxRows) {
        List<SearchRebuildService.RebuildReport> reports = new ArrayList<>(3);
        String s = scope == null ? "" : scope.trim().toLowerCase();
        switch (s) {
            case "tag-lexicon" -> reports.add(rebuildService.rebuildTagLexicon(dryRun, maxRows));
            case "user-index" -> reports.add(rebuildService.rebuildUserIndex(dryRun, maxRows));
            case "tag-index" -> reports.add(rebuildService.rebuildTagIndex(dryRun, maxRows));
            case "all" -> {
                reports.add(rebuildService.rebuildTagLexicon(dryRun, maxRows));
                reports.add(rebuildService.rebuildUserIndex(dryRun, maxRows));
                reports.add(rebuildService.rebuildTagIndex(dryRun, maxRows));
            }
            // 非法 scope 直接回空列表：宁可什么都不做，也不要「猜一个」然后全库扫一遍
            default -> {
                return Result.ok(List.of());
            }
        }
        return Result.ok(reports);
    }
}
