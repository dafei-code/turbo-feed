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
     * 内容过审入流（幂等：引擎侧同一 mediaId 重复投递先摘旧位置再写新位置）。
     *
     * @param item      时间线条目（仅 {@code status=APPROVED} 会被引擎写入）
     * @param poolLevel 信用等级对应的流量池层级
     */
    void append(MediaItem item, int poolLevel);

    /**
     * 内容移出公域（删除 / 下架 / 申诉中暂不可见）。
     *
     * @param mediaId 内容唯一标识；不存在时引擎侧静默成功（幂等）
     */
    void remove(String mediaId);
}
