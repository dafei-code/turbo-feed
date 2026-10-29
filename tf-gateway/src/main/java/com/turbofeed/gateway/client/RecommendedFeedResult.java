package com.turbofeed.gateway.client;

import com.turbofeed.shared.model.RecommendedItem;

import java.util.Map;

/**
 * 引擎推荐流响应的内部载体：展示用 {@link FeedRecommendedPage}（{@code MediaItem} 视图）
 * + 召回归因映射（timelineKey → {@link RecommendedItem}），供服务端曝光落盘时回填通道/池层级。
 *
 * <p><b>归因映射只在网关内部使用，不序列化给前端</b>：前端无需感知召回归因——曝光落盘由服务端完成，
 * 前端只需拿 {@link FeedRecommendedPage#requestId()} 在行为上报时原样回传，离线训练作业据此与
 * interaction_event 的 {@code request_id} 关联，间接获得每条内容的通道/池归属。故本载体不暴露
 * 任何 JSON 字段。</p>
 */
public record RecommendedFeedResult(FeedRecommendedPage page, Map<String, RecommendedItem> attribution) {
}
