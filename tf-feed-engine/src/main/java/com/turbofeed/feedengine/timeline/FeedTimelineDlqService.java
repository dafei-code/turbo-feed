package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 死信重放服务（P1-PR02：DLQ 死信重放管理接口）。
 *
 * <p><b>背景</b>：{@link FeedTimelineConsumer} 消费失败达 {@code maxReconusesTimes} 后进入
 * {@code %DLQ%{consumerGroup}}，原只有 runbook 人工巡检。本服务把"列出 + 重放"做成
 * 可灰度、可中断的运维接口，且不改动主消费链路。</p>
 *
 * <p><b>重放语义</b>：把死信消息<b>原样发回主 topic</b>（用 {@code syncSendOrderly} +
 * 原 {@code timelineKey} 作 hashKey，保证同一帖仍走同一队列、保持顺序），由<b>正常顺序消费者</b>
 * 重新处理——而非本服务直接调 {@link FeedTimelineDeliveryService#applyStrict}。理由：
 * 直接调会绕过顺序保证，重放与在途实时事件可能交错，再次制造"remove 先于 append"的内容安全事故。
 * 走主 topic 等于把死信重新喂回已被验证正确的唯一写路径。</p>
 *
 * <p><b>可灰度 / 不中断</b>：
 * <ul>
 *   <li>整个能力由 {@code turbofeed.feed.timeline.dlq-replay.enabled} 总开关控制，<b>默认关闭</b>，
 *       且必须 {@code turbofeed.mq.enabled=true}（死信本身依赖 MQ）；</li>
 *   <li>支持"按 mediaId 精确重放"或"全量重放"，运维可先小批验证再放量；</li>
 *   <li>单条重发失败只计数、不中断其余条，fail-open。</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = {com.turbofeed.shared.feature.FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED, com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED},
        havingValue = "true")
public class FeedTimelineDlqService {

    private final DlqMessageStore dlqStore;
    private final RocketMQTemplate rocketMQTemplate;
    private final String mainTopic;

    public FeedTimelineDlqService(DlqMessageStore dlqStore,
                                  RocketMQTemplate rocketMQTemplate,
                                  @Value("${turbofeed.feed.timeline.topic:turbofeed-feed-timeline}") String mainTopic) {
        this.dlqStore = dlqStore;
        this.rocketMQTemplate = rocketMQTemplate;
        this.mainTopic = mainTopic;
    }

    /** 列出当前死信（含 mediaId 便于筛选），只读、幂等。 */
    public List<DlqSummary> peek() {
        return dlqStore.peek().stream()
                .map(m -> new DlqSummary(m.msgId(),
                        m.event().action(),
                        mediaIdOf(m.event()),
                        m.event().timelineKey(),
                        m.bornTimestamp()))
                .toList();
    }

    /**
     * 重放死信。
     *
     * @param mediaIds 仅重放这些 mediaId 对应的死信
     * @param all      为 true 时重放全部（忽略 mediaIds）
     * @return 重放结果（命中 / 成功 / 失败）
     */
    public DlqReplayResult replay(Set<String> mediaIds, boolean all) {
        List<DlqMessage> allMsgs = dlqStore.peek();
        List<DlqMessage> targets = all
                ? allMsgs
                : allMsgs.stream()
                .filter(m -> mediaIdOf(m.event()) != null && mediaIds.contains(mediaIdOf(m.event())))
                .collect(Collectors.toList());
        int replayed = 0;
        int failed = 0;
        for (DlqMessage m : targets) {
            try {
                // 原样发回主 topic，保持顺序 hashKey = timelineKey
                rocketMQTemplate.syncSendOrderly(mainTopic, m.event(), hashKeyOf(m.event()));
                dlqStore.acknowledge(m);
                replayed++;
            } catch (Exception e) {
                failed++;
                log.error("死信重放失败（仍留 DLQ，可重试）: msgId={}, timelineKey={}, {}",
                        m.msgId(), m.event().timelineKey(), e.getMessage());
            }
        }
        return new DlqReplayResult(targets.size(), replayed, failed);
    }

    /** APPEND 取 item.mediaId；REMOVE 取 timelineKey（此时 item 为 null）。 */
    private static String mediaIdOf(FeedTimelineEvent event) {
        if (event == null) {
            return null;
        }
        return event.item() != null ? event.item().mediaId() : event.timelineKey();
    }

    /** 顺序 hashKey：与发送侧 {@code FeedTimelineEvent.append} 同口径，保证同一帖落同一队列。 */
    private static String hashKeyOf(FeedTimelineEvent event) {
        return event == null || event.timelineKey() == null ? "" : event.timelineKey();
    }
}
