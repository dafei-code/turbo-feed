package com.turbofeed.gateway.service.event;

import java.time.Instant;

/**
 * 媒体上传完成事件（Observer 模式的事件载体）。
 *
 * <p>单个文件上传成功、落存储后发布，订阅方包括：</p>
 * <ul>
 *   <li>审核服务（{@code ReviewListener} / {@code MediaReviewConsumer}）—— 进入待审并机审</li>
 *   <li>清理服务 / 通知服务 —— 本期占位，接入时新增订阅方即可，发布方零改动</li>
 * </ul>
 *
 * <p>事件为不可变 record，可安全跨线程 / 跨进程投递（RocketMQ 经 Jackson 序列化，
 * {@code Instant} 由 Spring Boot 默认注册的 JavaTimeModule 支持）。</p>
 *
 * <p><b>为什么事件要带 caption</b>：描述/标题是「上传完成」这一事实的一部分，审核通过时要
 * 随 {@code MediaItem} 物化进公域时间线（Redis ZSET），事件自带可避免消费方为拿描述再回查一次库。
 * 同时，兜底落库（{@code MediaReviewService.handleUploaded}）与首落库（上传服务）写入同一份
 * 描述，语义一致、重投不产生分叉。</p>
 *
 * @param mediaId     内容唯一标识（media/{userId}/{uuid}.{ext}），审核与查询的主键
 * @param userId      归属用户
 * @param url         本次上传文件的 URL（审核通过前不对公域暴露）
 * @param caption     描述/标题原文（抖音式文案：@用户 / #话题 / [image:idx:filename]；可空）
 * @param captionMark 描述解析后的结构化标记 JSON（由 CaptionMarkParser 产出；可空）
 * @param requestId   客户端幂等键（可空，透传自 X-Request-Id 请求头）
 * @param occurredAt  事件发生时间
 */
public record MediaUploadedEvent(
        String mediaId,
        String userId,
        String url,
        String caption,
        String captionMark,
        String requestId,
        Instant occurredAt) {
}
