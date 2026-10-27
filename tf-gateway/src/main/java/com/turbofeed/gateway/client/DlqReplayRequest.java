package com.turbofeed.gateway.client;

import java.util.Set;

/** 死信重放请求（网关 → 引擎内部接口）。 */
public record DlqReplayRequest(Set<String> mediaIds, boolean all) {
}
