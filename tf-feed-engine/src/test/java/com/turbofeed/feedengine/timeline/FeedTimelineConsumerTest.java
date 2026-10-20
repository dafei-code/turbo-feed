package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.TagIndexService;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MQ 顺序消息路径的标签索引维护（回归防护）。
 *
 * <p><b>背景</b>：{@code FeedTimelineStore} <b>不</b>维护 {@code tf:tag:media} 倒排索引，
 * 索引由投递路径各自显式维护。历史上消费者漏调 {@link TagIndexService}——
 * {@code mq.enabled=true}（生产形态）时索引既不建也不摘，且<b>无任何报错</b>：
 * 表现为「兴趣召回召回不到」「已下架内容仍留在倒排链」。本测试锁死这一行为。</p>
 *
 * <p><b>为什么不用 Spring</b>：纯 Mockito，毫秒级、不依赖 Redis/RocketMQ，可在每次提交跑。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineConsumerTest {

    private static final String POST_ID = "post/1001/2f7c9a";
    private static final List<String> TAGS = List.of("java", "后端");

    @Mock
    FeedTimelineStore feedTimelineStore;

    @Mock
    TagIndexService tagIndexService;

    @InjectMocks
    FeedTimelineConsumer consumer;

    private static FeedItemView item() {
        return new FeedItemView(
                POST_ID,
                "media/1001/a.jpg",
                "http://cdn/a.jpg",
                List.of("http://cdn/a.jpg"),
                FeedItemView.STATUS_APPROVED,
                Instant.now(),
                "一条带话题的文案 #java #后端",
                null,
                TAGS);
    }

    @Test
    @DisplayName("APPEND：时间线写入后同步建标签索引，键用 timelineKey")
    void appendIndexesTags() {
        FeedItemView view = item();

        consumer.onMessage(FeedTimelineEvent.append(view, 2));

        verify(feedTimelineStore).appendStrict(view, 2);
        verify(tagIndexService).index(POST_ID, TAGS);
    }

    @Test
    @DisplayName("REMOVE：时间线摘除后同步摘标签索引，键与 append 同口径")
    void removeDropsTags() {
        consumer.onMessage(FeedTimelineEvent.remove(POST_ID));

        verify(feedTimelineStore).removeStrict(POST_ID);
        verify(tagIndexService).remove(POST_ID);
    }

    @Test
    @DisplayName("未知 action：抛异常交给 MQ 重试，且绝不改任何索引（不能把坏消息当下架）")
    void unknownActionTouchesNothing() {
        FeedTimelineEvent weird =
                new FeedTimelineEvent(POST_ID, "PROMOTE", null, null, Instant.now());

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(weird));

        verifyNoInteractions(feedTimelineStore, tagIndexService);
    }

    @Test
    @DisplayName("时间线写入失败：异常冒泡触发 MQ 重试，且不提前建索引（先落时间线再建索引）")
    void appendFailurePropagatesBeforeIndexing() {
        FeedItemView view = item();
        doThrow(new IllegalStateException("redis down"))
                .when(feedTimelineStore).appendStrict(any(), anyInt());

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(FeedTimelineEvent.append(view, 1)));

        verifyNoInteractions(tagIndexService);
    }
}
