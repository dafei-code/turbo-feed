package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 死信重放的内部接口（服务间，非对外 API；与 {@code /internal/feed/**} 同前缀）。
 *
 * <p><b>开关</b>：仅当 {@code turbofeed.feed.timeline.dlq-replay.enabled=true} 且
 * {@code turbofeed.mq.enabled=true} 时注册。关闭时本控制器不存，网关侧调用拿到 404，
 * 由网关翻译为"功能未启用"，不影响主流程。</p>
 *
 * <p><b>权限</b>：本接口走内网、引擎不自带鉴权（与 {@code FeedTimelineController} 一致）。
 * 真正的权限闸门在网关 {@code AdminFeedTimelineDlqController}（{@code SYSTEM_CONFIG}）。</p>
 */
@RestController
@RequestMapping("/internal/feed/timeline/dlq")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {com.turbofeed.shared.feature.FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED, com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED},
        havingValue = "true")
public class FeedTimelineDlqController {

    private final FeedTimelineDlqService dlqService;

    /** 列出当前死信（只读、幂等）。 */
    @GetMapping
    public Result<List<DlqSummary>> list() {
        return Result.ok(dlqService.peek());
    }

    /**
     * 重放死信。
     *
     * @param req {@code mediaIds} 精确重放 / {@code all} 全量重放
     */
    @PostMapping("/replay")
    public Result<DlqReplayResult> replay(@RequestBody DlqReplayRequest req) {
        Set<String> mediaIds = req.mediaIds() == null ? Set.of() : req.mediaIds();
        return Result.ok(dlqService.replay(mediaIds, req.all()));
    }
}
