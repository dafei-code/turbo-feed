package com.turbofeed.gateway.service.counter;

import com.turbofeed.gateway.trace.TraceContext;
import com.turbofeed.shared.model.CounterIncrementEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计数增量 MQ 发布器（opt-in，{@code turbofeed.mq.enabled=true} 且
 * {@code turbofeed.counter.service.enabled=true} 时激活）。
 *
 * <p>顺序键用 {@code postId}，使同帖增量落到同队列、局部有序。与 {@link HttpCounterEventPublisher}
 * 互斥，保证「MQ 开/关」两种部署下只有一套 {@link CounterEventPublisher} Bean。</p>
 *
 * <p><b>突发档可靠性（优化项①）</b>：改造前发送失败只 WARN 就丢——post_counter 少记，
 * 且突发期恰恰是 broker 最容易抖的时候。现在三级兜底：</p>
 * <ol>
 *   <li>MQ 顺序投递（削峰主路径）；</li>
 *   <li>失败 → 同步 HTTP 直投 tf-counter（独立通道，复用 {@link HttpCounterEventPublisher#tryIncr}）；</li>
 *   <li>HTTP 也失败 → 进入<b>有界</b>本地重试队列，由 {@link #retryFailedIncrements()} 每 5s 重投，
 *       队列满则 ERROR 带 postId 留痕，供 B4 对账重建。</li>
 * </ol>
 *
 * <p>注意：这里的可靠性只针对「聚合底座 post_counter」，网关 Redis 实时计数始终不受影响；
 * 进程崩溃仍会丢重试队列中的增量（这是显式取舍，文档标注需对账），彻底兜底要靠 PR-E 的
 * 失败暂存（tf-counter 侧）与部署侧多副本。</p>
 */
public class RocketMqCounterEventPublisher implements CounterEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCounterEventPublisher.class);
    public static final String TOPIC = "turbofeed-counter-incr";

    /** 本地重试队列上限：防止 broker + tf-counter 同时长时间不可用时无限吃内存。 */
    private static final int MAX_RETRY_QUEUE = 10000;
    /** 单轮重投预算：避免积压时一个调度周期卡死。 */
    private static final int RETRY_BUDGET_PER_CYCLE = 500;

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;
    private final HttpCounterEventPublisher fallback;

    private final ConcurrentLinkedQueue<CounterIncrementEvent> retryQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retryQueueSize = new AtomicInteger();

    public RocketMqCounterEventPublisher(RocketMQTemplate rocketMQTemplate, String topic,
                                         HttpCounterEventPublisher fallback) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
        this.fallback = fallback;
    }

    @Override
    public void incr(String postId, String type, int delta) {
        CounterIncrementEvent event = new CounterIncrementEvent(postId, type, delta)
                .withTraceId(TraceContext.current());
        try {
            rocketMQTemplate.syncSendOrderly(topic, event, postId);
            return;
        } catch (Exception e) {
            log.error("计数增量投递 tf-counter(MQ)失败，转 HTTP 直投兜底: postId={}, type={}, delta={}",
                    postId, type, delta, e);
        }
        if (fallback.tryIncr(postId, type, delta)) {
            log.warn("计数增量 MQ→HTTP 兜底成功: postId={}, type={}, delta={}", postId, type, delta);
            return;
        }
        enqueueRetry(event);
    }

    private void enqueueRetry(CounterIncrementEvent event) {
        if (retryQueueSize.get() >= MAX_RETRY_QUEUE) {
            log.error("计数增量重试队列已满，丢弃（需 B4 对账重建）: postId={}, type={}, delta={}, queueSize={}",
                    event.postId(), event.type(), event.delta(), retryQueueSize.get());
            return;
        }
        requeue(event);
        log.warn("计数增量进入本地重试队列: postId={}, type={}, delta={}, queueSize={}",
                event.postId(), event.type(), event.delta(), retryQueueSize.get());
    }

    /** 直接放回队尾，不走容量检查：本轮失败的事件本来就占着队列名额，回灌不应再被判「满」。 */
    private void requeue(CounterIncrementEvent event) {
        retryQueue.offer(event);
        retryQueueSize.incrementAndGet();
    }

    /**
     * 每 5s 重投失败增量。本轮失败的事件统一收集，循环结束后再回灌队尾——
     * 否则同一条事件会在同一轮里被反复取出重试，白白吃掉整个预算。
     */
    @Scheduled(fixedDelayString = "${turbofeed.counter.mq-fallback-retry-ms:5000}")
    public void retryFailedIncrements() {
        int budget = RETRY_BUDGET_PER_CYCLE;
        int succeeded = 0;
        List<CounterIncrementEvent> stillFailing = new ArrayList<>();
        while (budget-- > 0) {
            CounterIncrementEvent e = retryQueue.poll();
            if (e == null) {
                break;
            }
            retryQueueSize.decrementAndGet();
            if (fallback.tryIncr(e.postId(), e.type(), e.delta())) {
                succeeded++;
            } else {
                stillFailing.add(e);
            }
        }
        for (CounterIncrementEvent e : stillFailing) {
            requeue(e);
        }
        if (succeeded > 0) {
            log.warn("计数增量本地重试成功: 本轮={}, 剩余待重试={}", succeeded, retryQueueSize.get());
        }
    }

    /** 诊断用：当前待重试增量数（管理端点 / 单测可观察）。 */
    public int pendingRetryCount() {
        return retryQueueSize.get();
    }
}
