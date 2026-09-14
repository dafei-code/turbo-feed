package com.turbofeed.gateway.service.event;

import java.time.Instant;
import java.util.List;

/**
 * 帖子上传完成事件（Observer 模式的事件载体）。
 *
 * <p><b>为什么是「帖级」而不是「文件级」</b>：一次上传批次 = 一个帖子。若按文件逐个发事件，
 * 审核侧就要为「9 张图各自触发的 9 次审核」做齐备性判断（第 1 次到的事件时其余 8 张可能
 * 还没落库），既引入竞态又需要额外的计数状态。改为一帖一个事件后，<b>事件到达即代表整帖
 * 已全部落库</b>：机审一次、整帖状态一次翻转、公域时间线一次投递，语义与「整帖一审」天然一致。</p>
 *
 * <p>订阅方：</p>
 * <ul>
 *   <li>审核服务（{@code ReviewListener} / {@code MediaReviewConsumer}）—— 进入待审并机审</li>
 *   <li>清理服务 / 通知服务 —— 本期占位，接入时新增订阅方即可，发布方零改动</li>
 * </ul>
 *
 * <p>事件为不可变 record，可安全跨线程 / 跨进程投递（RocketMQ 经 Jackson 序列化，
 * {@code Instant} 由 Spring Boot 默认注册的 JavaTimeModule 支持）。</p>
 *
 * <p><b>为什么事件要带 caption 与 urls</b>：描述/标题是「上传完成」这一事实的一部分，审核通过时
 * 要随条目物化进公域时间线（Redis ZSET），事件自带可避免消费方为拿描述、为拼轮播图片列表
 * 再回查一次库。同时，兜底落库（{@code MediaReviewService.handleUploaded}）与首落库（上传服务）
 * 写入同一份描述，语义一致、重投不产生分叉。</p>
 *
 * <p><b>{@code mediaIds} 与 {@code urls} 必须同序等长</b>：下标 i 的两个元素描述同一张图，
 * 且顺序即 {@code seq}（0 起）。消费方按此下标写回 {@code seq}，不要在两端各自排序。</p>
 *
 * @param postId      帖子唯一标识（{@code post/{userId}/{uuid}}）——整帖审核与整帖入流的操作对象
 * @param mediaIds    帖内全部媒体的标识，按 seq 升序；{@code mediaIds[0]} 为帖代表媒体
 * @param urls        帖内全部图片的可访问地址，按 seq 升序（与 {@code mediaIds} 同序等长）
 * @param userId      归属用户
 * @param caption     描述/标题原文（抖音式文案：@用户 / #话题 / [image:idx:filename]；可空）
 * @param captionMark 描述解析后的结构化标记 JSON（由 CaptionMarkParser 产出；可空）
 * @param requestId   客户端幂等键（可空，透传自 X-Request-Id 请求头）
 * @param occurredAt  事件发生时间
 */
public record MediaUploadedEvent(
        String postId,
        List<String> mediaIds,
        List<String> urls,
        String userId,
        String caption,
        String captionMark,
        String requestId,
        Instant occurredAt) {

    /**
     * 帖代表媒体 ID（首图）。审核状态读写与日志定位都以它为入口，因为
     * {@code media_id} 是主键、而 {@code post_id} 不是——按代表行定位帖是唯一精准的路径。
     *
     * <p><b>刻意不用 {@code getRepresentativeMediaId()} 命名</b>：record 上 {@code get} / {@code is}
     * 前缀的方法会被 Jackson 当作属性序列化进消息体，凭空污染契约
     * （与 {@code Result<T>.isSuccess()} 同类陷阱）。</p>
     *
     * @return 首图的 mediaId；{@code mediaIds} 为空时返回 {@code null}
     */
    public String representativeMediaId() {
        return mediaIds == null || mediaIds.isEmpty() ? null : mediaIds.get(0);
    }

    /** 帖内图片数（{@code mediaIds} 为空时为 0）。 */
    public int imageCount() {
        return mediaIds == null ? 0 : mediaIds.size();
    }
}
