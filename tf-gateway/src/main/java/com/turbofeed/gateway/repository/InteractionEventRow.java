package com.turbofeed.gateway.repository;

import java.time.Instant;

/**
 * 交互事件行视图（训练样本导出缝的载体）。
 *
 * <p>{@link InteractionEventRepository#listSince} 产出，供离线双塔作业（A1）消费。
 * position / page / channel / pool / requestId 均可空——服务端曝光目前只确定 user/item/position，
 * channel/pool 等富字段留待引擎侧发射时补全。</p>
 *
 * @param userId     行为发起者（已登录态）
 * @param itemId     内容主键（与 {@code MediaItem#timelineKey()} 口径一致：postId 或 mediaId 兜底）
 * @param eventType  事件类型：IMPRESSION / WATCH / PLAY_COMPLETE / LIKE / COMMENT / SHARE / DISLIKE
 * @param position   该条目在本次结果里的位次（position bias 校正用）
 * @param page       页码
 * @param channel    召回通道（热点/兴趣/流量池/向量/关注…），引擎侧补全
 * @param pool       流量池层级（1/2/3），引擎侧补全
 * @param requestId  本次列表拉取的请求 ID（归组"当次展示了什么"用）
 * @param createdAt  事件时间
 */
public record InteractionEventRow(long userId, String itemId, String eventType,
                                  Integer position, Integer page, String channel,
                                  Integer pool, String requestId, Instant createdAt) {
}
