package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedTimelineEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内死信缓冲（测试 / 演示用，零 broker 依赖）。
 *
 * <p>不注册为 Spring Bean——仅在单测中手动构造并注入 {@link FeedTimelineDlqService}，
 * 验证"按 mediaId 筛选 → 重发 → ack"的核心逻辑，无需 RocketMQ。</p>
 */
public class InMemoryDlqStore implements DlqMessageStore {

    private final List<DlqMessage> buffer = new CopyOnWriteArrayList<>();
    private long seq = 0;

    /** 测试注入一条死信（模拟 broker 里有积压）。 */
    public void inject(FeedTimelineEvent event) {
        buffer.add(new DlqMessage("mem-" + (++seq), "%DLQ%test", System.currentTimeMillis(), event));
    }

    @Override
    public List<DlqMessage> peek() {
        return new ArrayList<>(buffer);
    }

    @Override
    public void acknowledge(DlqMessage msg) {
        buffer.remove(msg);
    }
}
