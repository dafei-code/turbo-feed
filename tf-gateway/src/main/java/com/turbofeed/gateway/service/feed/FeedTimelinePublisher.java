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
     * <p><b>返回值是"投递是否成功"，不是"内容是否已进流"</b>：HTTP 实现只能确认请求是否送达，
     * 引擎内部还会再 fail-open 一次；MQ 实现只能确认消息是否发出。因此 true 不等于"一定可见"。
     * 之所以仍要返回值，是因为<b>调用方有权知道自己这次是不是白干了</b>——
     * 补投如果一律按成功计，报告就会在引擎全挂时显示「delivered=N, failed=0」，
     * 把一次彻底失败的补投包装成成功（这正是 P0-2 初版踩到的坑）。</p>
     *
     * @param item      帖子条目（仅 {@code status=APPROVED} 会被引擎写入）
     * @param poolLevel 信用等级对应的流量池层级
     * @return true = 本次投递成功送达；false = 投递失败（已记日志与健康度，调用方不必修）
     */
    boolean append(MediaItem item, int poolLevel);

    /**
     * 帖子移出公域（删除 / 下架 / 申诉中暂不可见）。
     *
     * <p><b>键必须与 append 时一致</b>：有 {@code postId} 用 postId，历史单图数据回退
     * {@code mediaId}（见 {@code FeedItemView#timelineKey()}）。传错键 → 引擎反查索引对不上 →
     * 下架静默失效、内容继续可见。</p>
     *
     * @param timelineKey 帖身份；不存在时引擎侧静默成功（幂等）
     * @return true = 投递成功送达；false = 投递失败
     */
    boolean remove(String timelineKey);
}
