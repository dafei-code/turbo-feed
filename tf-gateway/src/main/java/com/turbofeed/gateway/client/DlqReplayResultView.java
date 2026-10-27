package com.turbofeed.gateway.client;

/** 引擎死信重放结果（网关侧视图，与引擎 {@code DlqReplayResult} 同字段）。 */
public record DlqReplayResultView(int total, int replayed, int failed) {
}
