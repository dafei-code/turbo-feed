package com.turbofeed.gateway.service.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.gateway.service.review.MediaStatus;

import java.time.Instant;

/**
 * 媒体条目视图（查询侧返回模型，对应"我的上传"列表的一项）。
 *
 * <p><b>为什么单独建模</b>：上传接口只返回 URL 字符串，无法表达"这条内容当前处于
 * 什么审核状态"——而 UGC 场景下前端必须据此过滤展示（仅 APPROVED 可见）。
 * 本模型把"内容标识 + 访问地址 + 审核状态 + 时间"聚合为一项，供列表接口直接序列化。</p>
 *
 * <p><b>状态来源</b>：{@code status} 不在本模型内持久化，每次查询时由
 * {@link MediaQueryService} 向 {@code MediaReviewService} 实时取——审核状态与内容索引
 * 分属两个职责，避免状态双写不一致（单一事实源）。</p>
 *
 * <p><b>序列化约定（P2）</b>：项目编译未开启 {@code -parameters}，Jackson 在无参数名信息时
 * 无法反序列化 record 的 canonical 构造器。显式 {@link JsonProperty} 绑定 JSON 字段名，
 * 保证 Redis 缓存的 {@code List<MediaItem>} 能稳定往返，且与 IDEA 直接 run 的编译设置无关。</p>
 *
 * @param mediaId   内容唯一标识（media/{userId}/{uuid}.{ext}）
 * @param url       可访问地址
 * @param status    当前审核状态（未落库前为进程内态，重启丢失）
 * @param createdAt 内容创建（上传）时间
 */
public record MediaItem(
        @JsonProperty("mediaId") String mediaId,
        @JsonProperty("url") String url,
        @JsonProperty("status") MediaStatus status,
        @JsonProperty("createdAt") Instant createdAt) {
}
