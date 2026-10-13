package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * VectorRecallChannel 装饰器：在第四路向量召回上埋 fail-open 可观测性指标。
 *
 * <p><b>为什么装饰器只覆盖「外部可观测」部分</b>：延迟 / 候选扇入规模 / 返回条数都可在调用边界无感采集；
 * 但 {@link TagVectorRecallChannel} 内部的 fail-open（ann/bruteForce 两处 catch 返回空列表）异常被
 * <b>吞掉</b>，装饰器看不到——故精确「兜底触发次数」由 {@code TagVectorRecallChannel} 在 catch 内
 * 自计 {@code tf_feed_recall_vector_failopen_total}，两者互补，不重复。</p>
 *
 * <p><b>指标（Prometheus 前缀 {@code tf_feed_recall_vector_}）</b>：
 * <ul>
 *   <li>{@code duration_seconds} —— recall 调用延迟（Timer）；</li>
 *   <li>{@code candidates} —— 传入候选数（DistributionSummary，看召回扇入规模）；</li>
 *   <li>{@code returned_total} —— 召回返回条数（Counter）；</li>
 *   <li>{@code failopen_total} —— 召回内部 fail-open 兜底次数（见 {@code TagVectorRecallChannel}）。</li>
 * </ul>
 * 包住 {@link TagVectorRecallChannel}（唯一实现），本类升为 {@code @Primary} 注入 {@code FeedTimelineStore}。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Primary
@Component
public class InstrumentedRecallChannel implements VectorRecallChannel {

    private static final Logger log = LoggerFactory.getLogger(InstrumentedRecallChannel.class);

    private final VectorRecallChannel delegate;
    private final Timer timer;
    private final DistributionSummary candidates;
    private final Counter returned;

    public InstrumentedRecallChannel(VectorRecallChannel delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.timer = Timer.builder("tf_feed_recall_vector_duration_seconds")
                .description("向量召回 recall 调用延迟")
                .register(registry);
        this.candidates = DistributionSummary.builder("tf_feed_recall_vector_candidates")
                .description("向量召回传入候选数（召回扇入规模）")
                .register(registry);
        this.returned = Counter.builder("tf_feed_recall_vector_returned_total")
                .description("向量召回返回条数")
                .register(registry);
    }

    @Override
    public List<FeedItemView> recall(String userId, List<FeedItemView> candidatesIn, int topN) {
        if (candidatesIn != null && !candidatesIn.isEmpty()) {
            this.candidates.record(candidatesIn.size());
        }
        long start = System.nanoTime();
        try {
            List<FeedItemView> r = delegate.recall(userId, candidatesIn, topN);
            if (r != null && !r.isEmpty()) {
                returned.increment(r.size());
            }
            return r;
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
