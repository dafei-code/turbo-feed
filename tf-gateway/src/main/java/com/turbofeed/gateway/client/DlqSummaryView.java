package com.turbofeed.gateway.client;

/** 引擎死信列表项（网关侧视图，与引擎 {@code DlqSummary} 同字段）。 */
public record DlqSummaryView(String msgId, String action, String mediaId, String timelineKey, long bornTimestamp) {
}
