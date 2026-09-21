package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.Permission;
import com.turbofeed.gateway.security.RequirePermission;
import com.turbofeed.gateway.service.feed.FeedBackfillService;
import com.turbofeed.gateway.service.feed.FeedDeliveryHealth;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公域时间线的运维接口（补投 / 重建）。
 *
 * <p><b>为什么单独一个控制器</b>：{@code AdminMediaController} 管的是<b>审核动作</b>
 * （通过/驳回/下架/申诉），本控制器管的是<b>读模型的重建</b>——两者触发时机完全不同：
 * 前者是日常业务高频操作，后者只在"时间线与库里的审核状态不一致"这种事故下才用一次。
 * 混在一起会让「补投」看起来像常规操作，而它其实是带全分片扫描代价的重操作。</p>
 *
 * <p><b>权限取 {@link Permission#SYSTEM_CONFIG}</b>：补投会扫描全部分片并批量写引擎 Redis，
 * 属于系统级运维动作，比内容审核更高一级；{@code REVIEWER} 不应具备触发能力。</p>
 */
@RestController
@RequestMapping("/api/admin/feed")
public class AdminFeedController {

    private final FeedBackfillService backfillService;
    private final FeedDeliveryHealth deliveryHealth;

    public AdminFeedController(FeedBackfillService backfillService, FeedDeliveryHealth deliveryHealth) {
        this.backfillService = backfillService;
        this.deliveryHealth = deliveryHealth;
    }

    /**
     * 时间线投递健康度（只读）：快速回答"发现流为什么是空的"。
     *
     * <p>排查顺序建议：{@code readFailure > 0} → 引擎进程/网络问题；
     * {@code appendFailure > 0} → 有内容审核通过了却没进流，修复后跑一次
     * {@code /timeline/backfill}；{@code removeFailure > 0} → 已下架内容可能仍在展示，
     * <b>这类要优先处理</b>（合规风险高于内容不可见）。</p>
     *
     * <p><b>注意</b>：计数是<b>本实例</b>的进程内累计，多实例部署时需在每台上看。
     * 精确统计要等接入指标栈。</p>
     */
    @GetMapping("/timeline/delivery-health")
    @RequirePermission(Permission.DASHBOARD_VIEW)
    public Result<FeedDeliveryHealth.Snapshot> deliveryHealth() {
        return Result.ok(deliveryHealth.snapshot());
    }

    /**
     * 存量内容补投：扫描全平台 {@code APPROVED} 帖子并投递到 tf-feed-engine 时间线。
     *
     * <p><b>何时需要</b>：发现流空白但库里确实存在 APPROVED 内容（典型成因：引擎未启动、
     * 引擎 Redis 配置漂移、投递链路异常被 fail-open 静默吞掉）。</p>
     *
     * <p><b>幂等</b>：可重复调用，引擎侧按帖身份先摘旧再写新，不会产生重复条目。</p>
     *
     * <p><b>⚠️ 代价</b>：会跨分片广播扫库（{@code listApprovedGlobal}）。必须在
     * {@code maxPosts} 上设上限并避开流量高峰——这是运维动作，不是在线接口。</p>
     *
     * @param batchSize 单批条数（默认 50）
     * @param maxPosts  本次最多处理帖子数（默认 500，≤0 表示不限）
     */
    @PostMapping("/timeline/backfill")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<FeedBackfillService.BackfillReport> backfillTimeline(
            @RequestParam(value = "batchSize", defaultValue = "50") int batchSize,
            @RequestParam(value = "maxPosts", defaultValue = "500") int maxPosts) {
        return Result.ok(backfillService.backfillApproved(batchSize, maxPosts));
    }
}
