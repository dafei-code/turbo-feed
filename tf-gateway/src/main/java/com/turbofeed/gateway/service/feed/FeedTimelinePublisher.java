package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.service.query.MediaItem;

/**
 * Feed 时间线投递端口（防腐层出口）。
 *
 * <p>统一收网关自己的 {@link MediaItem}，对调用方屏蔽"投递到底用同步 HTTP 还是 RocketMQ"——
 * 由 {@code turbofeed.mq.enabled} 在 HTTP 兜底实现与 MQ 实现间二选一，业务层零改动。</p>
 */
public interface FeedTimelinePublisher {

    /**
     * 帖子过审入流（幂等：引擎侧同一帖重复投递先摘旧位置再写新位置）。
     *
     * <p><b>一帖一条</b>：{@code item} 是<b>帖子</b>（{@code images} 为整帖图片，按 seq 升序），
     * 不是单张图。一次上传的 N 张图对应<b>一次</b> append，引擎据此物化一条成员串、前端渲染轮播。</p>
     *
     * @param item      帖子条目（仅 {@code status=APPROVED} 会被引擎写入）
     * @param poolLevel 信用等级对应的流量池层级
     */
    void append(MediaItem item, int poolLevel);

    /**
     * 帖子移出公域（删除 / 下架 / 申诉中暂不可见）。
     *
     * <p><b>键必须与 append 时一致</b>：有 {@code postId} 用 postId，历史单图数据回退
     * {@code mediaId}（见 {@code FeedItemView#timelineKey()}）。传错键 → 引擎反查索引对不上 →
     * 下架静默失效、内容继续可见。</p>
     *
     * @param timelineKey 帖身份；不存在时引擎侧静默成功（幂等）
     */
    void remove(String timelineKey);
}
