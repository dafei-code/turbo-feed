package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产用死信读取实现：用 {@code DefaultLitePullConsumer} 订阅 {@code %DLQ%{consumerGroup}}。
 *
 * <p><b>为什么用 Lite Pull 而非 Push 消费者</b>：DLQ 重放是运维主动动作，不是常驻消费——
 * Pull 模式让我们<b>自己控制 offset</b>：{@code peek()} 只读不提交（可重复列出、幂等），
 * 仅在 {@code replay()} 成功重发后才 {@code acknowledge()}（提交 offset，消息离开 DLQ）。
 * 若用 Push 消费者，消息一被拉到就被 ack，重放失败也会丢失，违背"死信必须可重放"的初衷。</p>
 *
 * <p><b>健壮性</b>：broker 不可用 / 启动失败只告警、不抛——DLQ 重放是运维增强，
 * 绝不能因它拖垮引擎主流程（与现有 {@code fail-open} 基调一致）。</p>
 *
 * <p><b>独立 consumerGroup</b>：本巡检消费者用 {@code {group}-dlq-inspector} 作 group，
 * 与主顺序消费者 {@code turbofeed-feed-timeline-group} 的 offset 隔离，互不干扰。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"turbofeed.feed.timeline.dlq-replay.enabled", "turbofeed.mq.enabled"},
        havingValue = "true")
public class RocketMqDlqStore implements DlqMessageStore, InitializingBean, DisposableBean {

    private final ObjectMapper objectMapper;
    private final String consumerGroup;
    private final String namesrvAddr;
    private final String dlqTopic;

    private volatile DefaultLitePullConsumer consumer;
    /** msgId → MessageExt 缓冲（peek 幂等、ack 按 msgId 摘除）。 */
    private final Map<String, MessageExt> buffer = new ConcurrentHashMap<>();

    public RocketMqDlqStore(ObjectMapper objectMapper,
                            @Value("${turbofeed.feed.timeline.consumer-group:turbofeed-feed-timeline-group}") String consumerGroup,
                            @Value("${rocketmq.name-server:}") String namesrvAddr) {
        this.objectMapper = objectMapper;
        this.consumerGroup = consumerGroup;
        this.namesrvAddr = namesrvAddr == null ? "" : namesrvAddr;
        this.dlqTopic = "%DLQ%" + consumerGroup;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            DefaultLitePullConsumer c = new DefaultLitePullConsumer(consumerGroup + "-dlq-inspector");
            if (!namesrvAddr.isBlank()) {
                c.setNamesrvAddr(namesrvAddr);
            }
            // 订阅死信 topic 全量 tag；从最早 offset 开始，确保历史积压都能被列出
            c.subscribe(dlqTopic, "*");
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            c.start();
            this.consumer = c;
            log.info("DLQ 巡检消费者已启动: dlqTopic={}", dlqTopic);
        } catch (Exception e) {
            // 绝不抛：DLQ 重放不可用不应阻断引擎启动
            log.warn("DLQ 巡检消费者启动失败（DLQ 重放功能不可用，不影响主流程）: dlqTopic={}, {}",
                    dlqTopic, e.getMessage());
            this.consumer = null;
        }
    }

    @Override
    public List<DlqMessage> peek() {
        if (consumer == null) {
            return List.of();
        }
        try {
            // poll(200)：只取当前已拉到本地的、不阻塞；未提交 offset → 下次仍可见，列表幂等
            List<MessageExt> msgs = consumer.poll(200L);
            if (msgs != null) {
                for (MessageExt m : msgs) {
                    if (m.getMsgId() != null) {
                        buffer.putIfAbsent(m.getMsgId(), m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DLQ poll 失败（返回已缓冲部分）: {}", e.getMessage());
        }
        List<DlqMessage> out = new ArrayList<>(buffer.size());
        for (MessageExt m : buffer.values()) {
            FeedTimelineEvent ev = decode(m.getBody());
            if (ev != null) {
                out.add(new DlqMessage(m.getMsgId(), m.getTopic(), m.getBornTimestamp(), ev));
            }
        }
        return out;
    }

    @Override
    public void acknowledge(DlqMessage msg) {
        MessageExt removed = buffer.remove(msg.msgId());
        if (consumer != null && removed != null) {
            try {
                // 提交已消费位置：推进 offset，该消息离开 DLQ
                consumer.commitSync();
            } catch (Exception e) {
                log.warn("DLQ offset 提交失败（消息可能重复列出，重放幂等安全）: msgId={}, {}",
                        msg.msgId(), e.getMessage());
            }
        }
    }

    private FeedTimelineEvent decode(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.readValue(body, FeedTimelineEvent.class);
        } catch (Exception e) {
            log.warn("DLQ 消息体反序列化失败（跳过该条）: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception ignore) {
                // 关闭失败无补救意义
            }
        }
    }
}
