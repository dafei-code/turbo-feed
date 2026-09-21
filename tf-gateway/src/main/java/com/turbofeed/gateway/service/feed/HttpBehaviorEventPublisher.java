package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为埋点的<b>同步 HTTP 兜底</b>实现（默认，{@code turbofeed.mq.enabled=false} 或缺失时激活）。
 *
 * <p>委托 {@link FeedEngineClient#reportBehavior} 调引擎 {@code /internal/feed/behavior}。
 * 与时间线投递的 HTTP 兜底同语义（fail-open）。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "false", matchIfMissing = true)
public class HttpBehaviorEventPublisher implements BehaviorEventPublisher {

    private final FeedEngineClient feedEngineClient;

    public HttpBehaviorEventPublisher(FeedEngineClient feedEngineClient) {
        this.feedEngineClient = feedEngineClient;
    }

    @Override
    public void report(List<BehaviorReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        List<FeedBehaviorEvent> events = new ArrayList<>();
        for (BehaviorReport r : reports) {
            if (r != null && r.postId() != null) {
                events.add(new FeedBehaviorEvent(r.postId(), r.type()));
            }
        }
        feedEngineClient.reportBehavior(events);
    }
}
