package com.turbofeed.feedengine.timeline;

/**
 * 死信列表项（对外视图）：供运维快速定位"哪些内容卡在死信里"。
 *
 * @param msgId         broker 消息 ID
 * @param action        APPEND / REMOVE
 * @param mediaId       内容标识（APPEND 取 item.mediaId；REMOVE 取 timelineKey）
 * @param timelineKey   帖身份（顺序消息 hashKey）
 * @param bornTimestamp 消息 born 时间（epoch ms）
 */
public record DlqSummary(String msgId, String action, String mediaId, String timelineKey, long bornTimestamp) {
}
