package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流量池晋级的<b>内部接口</b>（服务间，{@code /internal/**}，非对外 API）。
 *
 * <p>路径约定与 {@link FeedTimelineController} 一致：统一挂在 {@code /internal/feed} 下，
 * 便于网关/Ingress 层拒绝外部访问。</p>
 *
 * <p><b>⚠️ 本类不再承载 {@code POST /internal/feed/behavior}</b>。历史上本类与
 * {@link FeedTimelineController} <b>各有一份</b> {@code /behavior} 实现，
 * 两个 {@code @PostMapping} 映射到同一路径 → Spring 启动报
 * {@code Ambiguous mapping} → <b>引擎根本起不来</b>（编译期发现不了，只有真实启动才暴露）。
 * 保留 {@link FeedTimelineController#behavior(List)} 为唯一入口：它处理完整
 * （WATCH 完播分支 + 兴趣画像 + 行为明细落盘），本类那份只调 {@code recordAll}，是残缺实现。
 * <b>不要在本类重新添加 {@code /behavior}</b>。</p>
 */
@RestController
@RequestMapping("/internal/feed")
public class PostStatController {

    private final PostStatService postStatService;
    private final FeedPoolPromoter poolPromoter;
    private final FeedTimelineStore timelineStore;

    public PostStatController(PostStatService postStatService, FeedPoolPromoter poolPromoter,
                              FeedTimelineStore timelineStore) {
        this.postStatService = postStatService;
        this.poolPromoter = poolPromoter;
        this.timelineStore = timelineStore;
    }

    // ⚠️ 这里曾有一份 POST /internal/feed/behavior，与 FeedTimelineController 重复导致
    //    Ambiguous mapping（引擎无法启动）。唯一入口现为 FeedTimelineController#behavior，
    //    详见本类 javadoc。勿再添加。

    /**
     * 手动触发一轮流量池晋级评估（运维/测试用；与定时逻辑同实现）。
     *
     * @return 本轮晋级条数
     */
    @PostMapping("/pool/promote-scan")
    public Result<Integer> promoteScan() {
        return Result.ok(poolPromoter.scanNow());
    }

    /**
     * 调试用：查看某帖当前所在流量池与实时分（运维/测试）。非对外 API。
     */
    @GetMapping("/pool/debug")
    public Result<PoolDebug> debug(@RequestParam("timelineKey") String timelineKey) {
        int pool = timelineStore.currentPool(timelineKey);
        PostStatService.PostStat s = postStatService.snapshot(timelineKey);
        return Result.ok(new PoolDebug(timelineKey, pool, s.impressions(), s.likes(),
                s.comments(), s.shares(), s.playCompletes(), s.dislikes()));
    }

    /** 调试快照：帖当前池 + 实时分。 */
    public record PoolDebug(String timelineKey, int pool, long impressions, long likes,
                             long comments, long shares, long playCompletes, long dislikes) {
    }
}
