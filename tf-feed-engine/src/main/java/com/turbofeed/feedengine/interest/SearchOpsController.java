package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索索引的<b>运维接口</b>（内部，非对外 API）。
 *
 * <p>路径沿用引擎既有约定挂在 {@code /internal/**} 下，与对外 {@code /api/**} 物理区分，
 * 便于在网关/Ingress 层拒绝外部访问；引擎自身不引入鉴权（当前阶段信任内网）。</p>
 *
 * <p>当前只有一个动作：<b>存量标签倒排的分数回填</b>。它不参与任何在线读写路径，
 * 只在发布后跑一次（或发现问题时重跑），因此<b>默认 dryRun=true</b>——
 * 运维必须显式传 {@code dryRun=false} 才会真正写入。</p>
 */
@RestController
@RequestMapping("/internal/search-ops")
public class SearchOpsController {

    private final TagScoreBackfillService backfillService;

    public SearchOpsController(TagScoreBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /**
     * 回填存量标签有序倒排的分数（幂等，可重复执行）。
     *
     * @param dryRun  只统计不写入；默认 {@code true}（先用它看清影响面，再落）
     * @param maxKeys 本次最多处理多少个标签键；{@code <=0} 用默认 2000（支持分批续跑）
     * @return 统计报告：扫了多少标签/成员、补了多少、多少因索引过期无法解析
     */
    @PostMapping("/backfill-tag-scores")
    public Result<TagScoreBackfillService.BackfillReport> backfillTagScores(
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(value = "maxKeys", defaultValue = "2000") int maxKeys) {
        return Result.ok(backfillService.backfill(dryRun, maxKeys));
    }
}
