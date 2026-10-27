package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedTimelineEvent;

/**
 * 一条死信队列（DLQ）消息的运行时快照。
 *
 * <p>RocketMQ 死信 topic 为 {@code %DLQ%{consumerGroup}}，消息体即原始
 * {@link FeedTimelineEvent}（APPEND/REMOVE）。本记录把 broker 侧 {@code MessageExt}
 * 解耦成可重放的纯结构，使重放核心逻辑<b>不依赖 broker</b>、可纯单测覆盖。</p>
 *
 * @param msgId         broker 消息 ID（用于 ack 时精确推进 offset）
 * @param topic         来源 topic（即 {@code %DLQ%...}）
 * @param bornTimestamp 消息 born 时间（epoch ms，仅展示用）
 * @param event         反序列化后的时间线事件（重放时原样发回主 topic）
 */
public record DlqMessage(String msgId, String topic, long bornTimestamp, FeedTimelineEvent event) {
}
