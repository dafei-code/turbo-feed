package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行为埋点 / 流量池晋级的<b>内部接口</b>（服务间，{@code /internal/**}，非对外 API）。
 *
 * <p>路径约定与 {@link FeedTimelineController} 一致：统一挂在 {@code /internal/feed} 下，
 * 便于网关/Ingress 层拒绝外部访问。行为事件由网关 {@code FeedBehaviorController} 经 HTTP 或
 * RocketMQ 投递到这里，落 {@link PostStatService} 的实时分。</p>
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

    /**
     * 接收行为埋点（批量，fail-open）。事件类型见 {@link FeedBehaviorEvent}。
     */
    @PostMapping("/behavior")
    public Result<Void> behavior(@RequestBody List<FeedBehaviorEvent> events) {
        postStatService.recordAll(events);
        return Result.ok();
    }

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
