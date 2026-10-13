package com.turbofeed.feedengine.ranking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RankingModel 装饰器：在精排打分热路径上埋 fail-open 可观测性指标，不改打分语义、不阻断主流程。
 *
 * <p><b>为什么是装饰器而非侵入实现</b>：{@link RankingModel} 契约承诺「不抛业务异常、无副作用」，
 * 任何实现（线性/DeepFM/多目标）都不该为埋点改动。本类包住 {@link SelectedRankingModel}
 * （原 {@code @Primary} 分发器），自身升为 {@code @Primary} 注入 {@code FeedTimelineStore}，
 * 对所有打分调用做<b>无感计时 + 计数</b>。</p>
 *
 * <p><b>指标（Prometheus 前缀 {@code tf_feed_ranking_}）</b>：
 * <ul>
 *   <li>{@code score_duration_seconds} —— 单次 score 调用延迟（Timer）；</li>
 *   <li>{@code score_calls_total} —— 精排打分调用总次数（Counter）；</li>
 *   <li>{@code score_failures_total} —— score 抛异常次数（Counter；契约禁止抛异常，理论上恒为 0，
 *       一旦非 0 即定位到某个打分实现违背了 fail-open 契约）。</li>
 * </ul>
 * </p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Primary
@Component
public class InstrumentedRankingModel implements RankingModel {

    private static final Logger log = LoggerFactory.getLogger(InstrumentedRankingModel.class);

    private final RankingModel delegate;
    private final Timer scoreTimer;
    private final Counter calls;
    private final Counter failures;

    public InstrumentedRankingModel(SelectedRankingModel delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.scoreTimer = Timer.builder("tf_feed_ranking_score_duration_seconds")
                .description("RankingModel.score 单次精排打分延迟")
                .register(registry);
        this.calls = Counter.builder("tf_feed_ranking_score_calls_total")
                .description("精排打分调用总次数")
                .register(registry);
        this.failures = Counter.builder("tf_feed_ranking_score_failures_total")
                .description("精排打分抛异常次数（RankingModel 契约禁止抛异常，应为 0）")
                .register(registry);
    }

    @Override
    public double score(RankingFeatures features) {
        calls.increment();
        long start = System.nanoTime();
        try {
            return delegate.score(features);
        } catch (RuntimeException e) {
            // 不吞异常：RankingModel 契约保证不抛，这里只是把「违背契约」的事实暴露成指标，
            // 原异常继续上抛，保持与改造前完全一致的行为（埋点零副作用）。
            failures.increment();
            throw e;
        } finally {
            scoreTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
