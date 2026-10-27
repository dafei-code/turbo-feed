package com.turbofeed.feedengine.timeline;

import java.util.List;

/**
 * 死信消息的读取 / 确认抽象。
 *
 * <p>把"从哪读 DLQ"与"怎么重放"解耦：
 * <ul>
 *   <li>{@link RocketMqDlqStore} —— 生产实现，用 {@code DefaultLitePullConsumer} 订阅
 *       {@code %DLQ%{consumerGroup}}；</li>
 *   <li>{@link InMemoryDlqStore} —— 测试 / 演示实现，进程内缓冲，零 broker 依赖。</li>
 * </ul>
 * 重放服务只认本接口，因此核心逻辑（按 mediaId 筛选 → 重发主 topic → ack）可脱离
 * RocketMQ 完全单测。</p>
 */
public interface DlqMessageStore {

    /**
     * 看一眼当前死信（不提交 offset，可重复调用，幂等）。
     *
     * @return 当前缓冲的死信快照；无 / broker 不可用时返回空列表
     */
    List<DlqMessage> peek();

    /**
     * 确认一条死信已处理（重放成功后调用，推进 offset 使其离开 DLQ）。
     *
     * @param msg 待确认的消息（须来自 {@link #peek()} 的返回）
     */
    void acknowledge(DlqMessage msg);
}
