package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedTimelineDlqService} 重放核心的纯单测（零 broker）：用 {@link InMemoryDlqStore}
 * 充当 DLQ 源、Mockito Mock {@link RocketMQTemplate} 充当主 topic 发送，验证
 * "按 mediaId 筛选 → 原样重发主 topic（保持顺序 hashKey）→ ack" 的核心逻辑与计数语义。
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineDlqServiceTest {

    private static final String MAIN_TOPIC = "turbofeed-feed-timeline";

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private static FeedItemView approvedItem(String postId, String mediaId) {
        return new FeedItemView(postId, mediaId, "http://x/" + mediaId, null,
                FeedItemView.STATUS_APPROVED, Instant.now(), "cap", null, null);
    }

    private FeedTimelineDlqService serviceWith(InMemoryDlqStore store) {
        return new FeedTimelineDlqService(store, rocketMQTemplate, MAIN_TOPIC);
    }

    @Test
    void peek_extracts_mediaId_for_append_and_remove() {
        InMemoryDlqStore store = new InMemoryDlqStore();
        store.inject(FeedTimelineEvent.append(approvedItem("post-1", "media-1"), 1));
        store.inject(FeedTimelineEvent.remove("tk-2"));

        List<DlqSummary> summaries = serviceWith(store).peek();

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).action()).isEqualTo("APPEND");
        assertThat(summaries.get(0).mediaId()).isEqualTo("media-1");
        assertThat(summaries.get(1).action()).isEqualTo("REMOVE");
        assertThat(summaries.get(1).mediaId()).isEqualTo("tk-2");
    }

    @Test
    void replay_by_mediaId_resends_only_matching_and_acks() {
        InMemoryDlqStore store = new InMemoryDlqStore();
        store.inject(FeedTimelineEvent.append(approvedItem("post-1", "media-1"), 1));
        store.inject(FeedTimelineEvent.append(approvedItem("post-2", "media-2"), 1));
        FeedTimelineDlqService service = serviceWith(store);

        DlqReplayResult result = service.replay(Set.of("media-1"), false);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        // 仅 media-1 被重发，且 hashKey = 其 timelineKey（postId）
        verify(rocketMQTemplate, times(1)).syncSendOrderly(eq(MAIN_TOPIC), any(FeedTimelineEvent.class), eq("post-1"));
        verify(rocketMQTemplate, never()).syncSendOrderly(eq(MAIN_TOPIC), any(FeedTimelineEvent.class), eq("post-2"));
        // 重发成功 → 该死信被 ack（离开缓冲）
        assertThat(store.peek()).hasSize(1);
        assertThat(store.peek().get(0).event().item().mediaId()).isEqualTo("media-2");
    }

    @Test
    void replay_all_resends_everything() {
        InMemoryDlqStore store = new InMemoryDlqStore();
        store.inject(FeedTimelineEvent.append(approvedItem("post-1", "media-1"), 1));
        store.inject(FeedTimelineEvent.remove("tk-2"));
        FeedTimelineDlqService service = serviceWith(store);

        DlqReplayResult result = service.replay(Set.of(), true);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.replayed()).isEqualTo(2);
        assertThat(store.peek()).isEmpty();
        verify(rocketMQTemplate, times(2)).syncSendOrderly(eq(MAIN_TOPIC), any(FeedTimelineEvent.class), any(String.class));
    }

    @Test
    void replay_failure_is_counted_and_message_stays_in_dlq() {
        InMemoryDlqStore store = new InMemoryDlqStore();
        store.inject(FeedTimelineEvent.append(approvedItem("post-1", "media-1"), 1));
        when(rocketMQTemplate.syncSendOrderly(eq(MAIN_TOPIC), any(FeedTimelineEvent.class), any(String.class)))
                .thenThrow(new RuntimeException("broker down"));
        FeedTimelineDlqService service = serviceWith(store);

        DlqReplayResult result = service.replay(Set.of("media-1"), false);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.replayed()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        // 重发失败 → 不 ack，仍留 DLQ（可重试）
        assertThat(store.peek()).hasSize(1);
    }
}
