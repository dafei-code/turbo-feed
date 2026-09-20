package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 事件体：帖子移出公域。
 *
 * @param timelineKey 帖身份（有 postId 用 postId，历史单图数据回退 mediaId）——
 *                    必须与 {@code append} 时用的键一致，否则引擎反查索引对不上，下架静默失效。
 */
public record TimelineRemovePayload(
        @JsonProperty("timelineKey") String timelineKey) {
}
