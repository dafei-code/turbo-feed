package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 死信积压告警巡检（P0-2 DLQ 告警部署接线）。
 *
 * <p><b>为什么需要</b>：此前死信进入 DLQ 后只静默积压，直到运维人工跑 {@code dlqList} 才发现——
 * 等于"内容事故要靠人盯着"。本服务把"DLQ 非空即报警"做成定时巡检，让死信积压第一时间触达
 * 值守（日志 ERROR + 可选 webhook），把发现时效从"人工轮询"降到"秒级自动"。</p>
 *
 * <p><b>触发口径</b>：当前 DLQ 积压条数 {@code >= threshold} 且距上次告警已过 {@code cooldown-ms}
 * 才发——阈值防误报（偶发 1 条无需惊动），冷却防告警风暴（积压未消时每轮都报会刷爆渠道）。
 * 阈值/冷却/巡检间隔/样例上限/实例标识/ webhook 均配置化，部署按预案接线。</p>
 *
 * <p><b>fail-open</b>：巡检自身异常（store 不可用等）只告警、绝不抛；告警出口（{@link DlqNotifier}）
 * 同样 fail-open。本服务是运维增强，绝不能因它拖垮时间线主链路。</p>
 *
 * <p><b>生命周期</b>：仅在 {@code dlq-alert.enabled} + {@code dlq-replay.enabled} + {@code mq.enabled}
 * 同时为 true 时注册——前两者缺任一，DLQ 巡检消费者（{@link RocketMqDlqStore}）都不存在，告警无从读起。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = {
        FeatureFlagKeys.FEED_TIMELINE_DLQ_ALERT_ENABLED,
        FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED,
        FeatureFlagKeys.MQ_ENABLED
}, havingValue = "true")
public class DlqAlertService {

    private final DlqMessageStore dlqStore;
    private final DlqNotifier notifier;
    private final int threshold;
    private final long cooldownMs;
    private final int sampleLimit;
    private final String engineId;

    /** 上次告警时刻（Epoch millis），冷却判定用；volatile 保证定时线程可见性。 */
    private volatile long lastAlertAt = 0;

    public DlqAlertService(DlqMessageStore dlqStore,
                           DlqNotifier notifier,
                           @Value("${turbofeed.feed.timeline.dlq-alert.threshold:1}") int threshold,
                           @Value("${turbofeed.feed.timeline.dlq-alert.cooldown-ms:300000}") long cooldownMs,
                           @Value("${turbofeed.feed.timeline.dlq-alert.sample-limit:20}") int sampleLimit,
                           @Value("${turbofeed.feed.timeline.dlq-alert.engine-id:}") String engineId) {
        this.dlqStore = dlqStore;
        this.notifier = notifier;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.sampleLimit = sampleLimit;
        this.engineId = engineId == null ? "" : engineId;
    }

    /** 定时巡检（部署可调间隔，默认 60s）。 */
    @Scheduled(fixedDelayString = "${turbofeed.feed.timeline.dlq-alert.check-interval-ms:60000}")
    public void scheduledCheck() {
        check();
    }

    /**
     * 巡检一次：DLQ 积压达阈值且过了冷却期则发告警。
     *
     * <p>抽取为包级可调用方法，便于管理接口强制触发与单测（绕过 @Scheduled 时间窗口）。</p>
     */
    public void check() {
        try {
            List<DlqMessage> msgs = dlqStore.peek();
            int size = (msgs == null) ? 0 : msgs.size();
            long now = System.currentTimeMillis();
            if (size >= threshold && (now - lastAlertAt) >= cooldownMs) {
                List<String> samples = buildSamples(msgs);
                notifier.notify(new DlqAlert(size, samples, engineId, now));
                lastAlertAt = now;
                log.info("DLQ 告警已触发: count={}, engineId={}", size, engineId);
            }
        } catch (Exception e) {
            // 巡检自身失败绝不影响主流程：仅告警，下次周期再试
            log.warn("DLQ 告警巡检失败（fail-open，下个周期重试）: {}", e.getMessage());
        }
    }

    /** 当前是否已经过了冷却期（供管理接口/测试判断），不改变状态。 */
    public boolean isInCooldown(long now) {
        return (now - lastAlertAt) < cooldownMs;
    }

    /** 取前 {@code sampleLimit} 条死信的 mediaId（APPEND 取 item.mediaId，REMOVE 取 timelineKey）。 */
    private List<String> buildSamples(List<DlqMessage> msgs) {
        List<String> samples = new ArrayList<>();
        if (msgs == null) {
            return samples;
        }
        int limit = Math.min(sampleLimit, msgs.size());
        for (int i = 0; i < limit; i++) {
            FeedTimelineEvent ev = msgs.get(i).event();
            String id = null;
            if (ev != null) {
                FeedItemView item = ev.item();
                id = item != null ? item.mediaId() : ev.timelineKey();
            }
            if (id != null) {
                samples.add(id);
            }
        }
        return samples;
    }
}
