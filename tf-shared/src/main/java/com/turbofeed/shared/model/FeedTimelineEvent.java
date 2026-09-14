package com.turbofeed.shared.model;

import java.time.Instant;

/**
 * Feed 时间线变更事件（B2：RocketMQ 顺序消息载体，网关 → 引擎）。
 *
 * <p>append 与 remove <b>共用同一个 topic、同一个消费者</b>，用 {@code action} 区分——
 * 发送侧以 {@link #timelineKey} 作为顺序 hashKey（{@code syncSendOrderly}），
 * 保证同一帖的 append / remove 严格按序，杜绝「remove 先于 append 执行」导致已下架内容复现的
 * 内容安全事故。绝不能按 action 拆 tag / 拆消费者，否则同一帖会落到不同队列、失去顺序保证。</p>
 *
 * <p><b>键是「帖身份」而不是 mediaId</b>：一帖多图下，同一 {@code postId} 的 N 张图共用一条
 * 时间线成员，下架也必须按帖整体摘除。键的口径由 {@link FeedItemView#timelineKey()} 统一定义
 * ——有 {@code postId} 用 postId，历史单图数据回退 {@code mediaId}。发送侧与消费侧必须用同一个键，
 * 否则反查索引对不上，下架会静默失效。</p>
 *
 * <p><b>零依赖定位</b>：本类落在 tf-shared，<b>不引入任何 Jackson 注解</b>——
 * 反序列化依赖 tf-shared 的 {@code -parameters} 编译参数（形参名随 .class 暴露），
 * 与 {@link FeedItemView} 同机制。{@code Instant} 由 Spring Boot 默认注册的 JavaTimeModule 序列化。</p>
 *
 * @param timelineKey 帖身份（顺序消息 hashKey，保证同一帖严格有序）；
 *                    见 {@link FeedItemView#timelineKey()} 的回退口径
 * @param action      操作类型：{@link #ACTION_APPEND} / {@link #ACTION_REMOVE}
 * @param poolLevel   流量池层级（仅 APPEND 有值；REMOVE 为 null）
 * @param item        帖子条目（仅 APPEND 有值；REMOVE 为 null）
 * @param occurredAt  事件发生时间
 */
public record FeedTimelineEvent(
        String timelineKey,
        String action,
        Integer poolLevel,
        FeedItemView item,
        Instant occurredAt) {

    /** 入流（审核通过 / 申诉翻案）。 */
    public static final String ACTION_APPEND = "APPEND";
    /** 下架（删除 / 举报下架 / 申诉中暂不可见）。 */
    public static final String ACTION_REMOVE = "REMOVE";

    // ⚠️ 刻意【不提供】isAppend() / isRemove() 这类 is 前缀布尔方法。
    // 实测：Jackson 会把 isXxx() 当作 boolean getter 序列化出去，线上 body 里因此多出
    //   "append":true,"remove":false
    // 既污染消息格式，又埋下「一旦开启严格反序列化整条消息解析失败」的隐患
    // （与 tf-shared 里 Result<T> 的 isSuccess() 属同一类问题：写得出去、读法不对等）。
    // tf-shared 是零第三方依赖模块，不能用 @JsonIgnore 规避，因此从命名上绕开：
    // 调用方直接比较 ACTION_APPEND / ACTION_REMOVE 常量即可（见引擎侧 FeedTimelineConsumer）。

    /**
     * 入流工厂：item 必填（帖子条目，含整帖 images），poolLevel 由账号信用等级决定。
     *
     * <p>hashKey 取 {@link FeedItemView#timelineKey()}，与 {@link #remove(String)} 的入参口径一致
     * ——这是「同一帖的 append / remove 落同一队列」的前提。</p>
     */
    public static FeedTimelineEvent append(FeedItemView item, int poolLevel) {
        return new FeedTimelineEvent(
                item == null ? null : item.timelineKey(),
                ACTION_APPEND,
                poolLevel,
                item,
                Instant.now());
    }

    /** 下架工厂：item 为 null（精确摘除只需帖身份）。 */
    public static FeedTimelineEvent remove(String timelineKey) {
        return new FeedTimelineEvent(timelineKey, ACTION_REMOVE, null, null, Instant.now());
    }
}
