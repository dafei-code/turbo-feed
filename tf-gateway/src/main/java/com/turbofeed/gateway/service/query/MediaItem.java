package com.turbofeed.gateway.service.query;

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
 * @param mediaId   内容唯一标识（media/{userId}/{uuid}.{ext}）
 * @param url       可访问地址
 * @param status    当前审核状态（未落库前为进程内态，重启丢失）
 * @param createdAt 内容创建（上传）时间
 */
public record MediaItem(
        String mediaId,
        String url,
        MediaStatus status,
        Instant createdAt) {
}
