package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.service.query.MediaItem;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feed 时间线投递的<b>同步 HTTP 兜底</b>实现（默认，{@code turbofeed.mq.enabled=false} 或缺失时激活）。
 *
 * <p>委托现有 {@link FeedEngineClient} 调引擎 {@code /internal/feed/timeline/*}，
 * 行为与 B2 改造前完全一致（fail-open）。保留它是因为 mq 关闭时仍需一条可用投递路径，
 * 且它是<b>唯一仍直接调用</b> {@code FeedEngineClient#append}/{@code #remove} 的地方。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED, havingValue = "false", matchIfMissing = true)
public class HttpFeedTimelinePublisher implements FeedTimelinePublisher {

    private final FeedEngineClient feedEngineClient;

    @Override
    public boolean append(MediaItem item, int poolLevel) {
        return feedEngineClient.append(item, poolLevel);
    }

    @Override
    public boolean remove(String timelineKey) {
        return feedEngineClient.remove(timelineKey);
    }

    /** PR-D3：把 enqueue 时刻作为 query 参数透传给引擎，由引擎写墓碑 / 比对陈旧 APPEND。 */
    @Override
    public boolean append(MediaItem item, int poolLevel, Instant occurredAt) {
        return feedEngineClient.append(item, poolLevel, occurredAt);
    }

    /** PR-D3：同上，REMOVE 侧透传。{@code null} 时不带参数，引擎按存量事件放行。 */
    @Override
    public boolean remove(String timelineKey, Instant occurredAt) {
        return feedEngineClient.remove(timelineKey, occurredAt);
    }
}
