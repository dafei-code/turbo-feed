package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.client.FeedItemMapper;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feed 时间线投递的 <b>RocketMQ 顺序消息</b>实现（{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>用 {@link FeedItemMapper} 把网关 {@link MediaItem} 转成跨服务契约 {@link FeedItemView}，
 * 再构造 {@link FeedTimelineEvent} 经 {@code syncSendOrderly(topic, event, mediaId)} 投递——
 * hashKey=mediaId 保证同一媒体的 append / remove 落同一队列、严格有序。</p>
 *
 * <p><b>fail-fast（关键）</b>：投递失败<b>直接抛异常</b>，<b>不写任何降级 / 本地兜底</b>。
 * 降级会掩盖失败，让 MQ 的持久化 / 重试 / 削峰全部失效——正是 B2 要消灭的静默丢失。
 * 审核 / 删除路径靠 {@code @Transactional} 回滚保证不丢：MQ 不可用 → 事务回滚 → 对外可见失败，
 * 比"内容悄悄不入流"更安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
public class RocketMqFeedTimelinePublisher implements FeedTimelinePublisher {

    /** Feed 时间线事件 Topic，与引擎侧 {@code FeedTimelineConsumer} 共用同一配置键。 */
    public static final String TOPIC = "turbofeed-feed-timeline";

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${turbofeed.feed.timeline.topic:turbofeed-feed-timeline}")
    private String topic;

    @Override
    public boolean append(MediaItem item, int poolLevel) {
        if (item == null) {
            return false;
        }
        FeedItemView view = FeedItemMapper.toContract(item);
        FeedTimelineEvent event = FeedTimelineEvent.append(view, poolLevel);
        // hashKey 必须与 remove 用同一个键（view.timelineKey()：有 postId 用 postId，历史数据回退 mediaId），
        // 否则同一帖的 append 与 remove 会落不同队列、失去顺序保证，
        // 出现「remove 先执行、append 后执行 → 已下架内容重新出现」的内容安全事故。
        // syncSendOrderly 失败（no route / 超时 / 连接失败）直接抛异常 → 由调用方事务回滚
        rocketMQTemplate.syncSendOrderly(topic, event, view.timelineKey());
        // 未抛异常即视为投递成功（本实现是 fail-fast：失败直接抛，不走返回值通道）
        return true;
    }

    @Override
    public boolean remove(String timelineKey) {
        if (timelineKey == null) {
            return false;
        }
        FeedTimelineEvent event = FeedTimelineEvent.remove(timelineKey);
        // 与 append 同键，保证同一帖严格有序
        rocketMQTemplate.syncSendOrderly(topic, event, timelineKey);
        return true;
    }
}
