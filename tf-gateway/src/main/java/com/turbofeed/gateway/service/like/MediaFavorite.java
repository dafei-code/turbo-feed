package com.turbofeed.gateway.service.like;

import java.time.Instant;

/**
 * 视频收藏持久化实体（{@code media_favorite} 表）。
 *
 * <p><b>分片键 {@code post_id}</b>：收藏的实体是「一条帖子（视频）」而非单张图，与公域时间线
 * 幂等键 {@code FeedItemView#timelineKey()}（即 {@code postId}）口径一致——这样 Redis 去重集合
 * {@code tf:favorite:users:{postId}}、引擎实时分 {@code tf:post:stat:{postId}}、持久化表三处
 * 用同一个身份，避免「同一条视频在去重集合与统计里被算成两个键」。</p>
 *
 * <p><b>为什么还要落库</b>：Redis Set 是收藏的<b>单一事实源与去重权威</b>（SADD 原子去重），
 * 但 Redis 重启会丢。{@code media_favorite} 提供持久化兜底——重启后可据此重建 Set
 * （运维脚本/启动预热，本类仅负责写入，重建留作独立能力）。收藏是高频小写入，
 * 与 {@code media_like} 表同走 ShardingSphere（{@code hashMod128}，post_id 分片）。</p>
 */
public record MediaFavorite(long id, String postId, long userId, Instant createdAt) {
}
