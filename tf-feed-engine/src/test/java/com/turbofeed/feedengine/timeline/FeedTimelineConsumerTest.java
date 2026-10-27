package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * MQ 顺序消息通道的行为（回归防护）+ M4 消费失败率指标。
 *
 * <p><b>背景</b>：{@code FeedTimelineStore} <b>不</b>维护 {@code tf:tag:media} 倒排索引，
 * 索引由投递路径维护。历史上消费者漏调 {@code TagIndexService}——{@code mq.enabled=true}（生产形态）时
 * 索引既不建也不摘，且<b>无任何报错</b>：表现为「兴趣召回召回不到」「已下架内容仍留在倒排链」。</p>
 *
 * <p><b>R7 之后本测试守护什么</b>：消费者不再自己拼副作用，而是把事件透传给
 * {@link FeedTimelineDeliveryService#applyStrict}（唯一入口）。因此这里锁死两件事：
 * <ol>
 *   <li>事件被<b>原样</b>透传给严格入口（通道只决定传输，不改写语义）；</li>
 *   <li>入口抛出的异常<b>必须冒泡</b>——否则 RocketMQ 重试与 DLQ 永不触发。</li>
 * </ol>
 * 「三条副作用齐全」与「未知 action 不按 REMOVE 兜底」由
 * {@link FeedTimelineDeliveryServiceTest} 锁死，此处不重复。</p>
 *
 * <p><b>M4 新增</b>：失败率指标。只记失败数算不出比率——「每秒 3 次失败」在总量 3000/s 是噪声、
 * 在 5/s 是全线故障。故成功计分母（{@code tf_feed_timeline_consume_total}）、失败计分子
 * （{@code tf_feed_timeline_consume_failed_total}），均按 action 分标签（REMOVE 失败更急）。</p>
 *
 * <p><b>为什么不用 Spring</b>：纯 Mockito + {@link SimpleMeterRegistry}，毫秒级、
 * 不依赖 Redis/RocketMQ，可在每次提交跑。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineConsumerTest {

    private static final String POST_ID = "post/1001/2f7c9a";
    private static final List<String> TAGS = List.of("java", "后端");

    @Mock
    FeedTimelineDeliveryService feedTimelineDelivery;

    private SimpleMeterRegistry registry;
    private FeedTimelineConsumer consumer;

    @BeforeEach
    void setUp() {
        // 显式构造而非 @InjectMocks：M4 起构造器多了一个 MeterRegistry，mock 掉它会让
        // Counter.builder(...).register(mock) 返回 null，失败路径上的 increment() 直接 NPE。
        registry = new SimpleMeterRegistry();
        consumer = new FeedTimelineConsumer(feedTimelineDelivery, registry);
    }

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

    /**
     * 读计数器；<b>时序不存在时按 0 计</b>——「没建过这个计数器」与「计数为 0」在业务上等价，
     * 但 {@code MeterRegistry#get} 在前者会抛 {@code MeterNotFoundException}。
     * 是否真的不存在由需要断言的用例单独用 {@code find(...) == null} 检查。
     */
    private double count(String name, String action) {
        var counter = registry.find(name).tag("action", action).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double total(String action) {
        return count("tf_feed_timeline_consume_total", action);
    }

    private double failed(String action) {
        return count("tf_feed_timeline_consume_failed_total", action);
    }

    @Test
    @DisplayName("APPEND：事件原样透传给严格投递入口（含池层级与帖身份）")
    void appendDelegatesToStrictEntry() {
        FeedItemView view = item();

        consumer.onMessage(FeedTimelineEvent.append(view, 2));

        ArgumentCaptor<FeedTimelineEvent> captor = ArgumentCaptor.forClass(FeedTimelineEvent.class);
        verify(feedTimelineDelivery).applyStrict(captor.capture());
        FeedTimelineEvent passed = captor.getValue();
        assertEquals(FeedTimelineEvent.ACTION_APPEND, passed.action(), "action 不得被改写");
        assertEquals(POST_ID, passed.timelineKey(), "顺序 hashKey 必须是帖身份");
        assertEquals(2, passed.poolLevel(), "流量池层级不得丢失");
        assertEquals(TAGS, passed.item().tags(), "标签随事件透传（入口据此建倒排索引）");
    }

    @Test
    @DisplayName("REMOVE：事件原样透传，键与 append 同口径")
    void removeDelegatesToStrictEntry() {
        consumer.onMessage(FeedTimelineEvent.remove(POST_ID));

        ArgumentCaptor<FeedTimelineEvent> captor = ArgumentCaptor.forClass(FeedTimelineEvent.class);
        verify(feedTimelineDelivery).applyStrict(captor.capture());
        assertEquals(FeedTimelineEvent.ACTION_REMOVE, captor.getValue().action());
        assertEquals(POST_ID, captor.getValue().timelineKey());
    }

    @Test
    @DisplayName("未知 action：入口拒绝后异常冒泡给 MQ 重试（绝不按 REMOVE 兜底）")
    void unknownActionPropagates() {
        FeedTimelineEvent weird = new FeedTimelineEvent(POST_ID, "PROMOTE", null, null, Instant.now());
        doThrow(new IllegalArgumentException("拒绝按 REMOVE 兜底"))
                .when(feedTimelineDelivery).applyStrict(any());

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(weird),
                "消费者不得吞掉异常：吞掉则 MQ 重试与 DLQ 永不触发");
    }

    @Test
    @DisplayName("投递失败：异常原样冒泡，交给 MQ 重试/DLQ")
    void deliveryFailurePropagates() {
        doThrow(new IllegalStateException("redis down"))
                .when(feedTimelineDelivery).applyStrict(any());

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(FeedTimelineEvent.append(item(), 1)));
    }

    @Test
    @DisplayName("M4 成功：计入失败率的分母，且不创建失败时序")
    void success_countsDenominatorOnly() {
        consumer.onMessage(FeedTimelineEvent.append(item(), 1));

        assertEquals(1.0, total(FeedTimelineEvent.ACTION_APPEND), 1e-9);
        assertEquals(0.0, failed(FeedTimelineEvent.ACTION_APPEND), 1e-9);
        // 成功路径不该创建失败计数器：凭空造出「失败率 0」的时序会让 absent() 类规则失效，
        // 也掩盖「这条链路根本没失败过」与「失败率确实为 0」的区别。
        assertNull(registry.find("tf_feed_timeline_consume_failed_total")
                .tag("action", FeedTimelineEvent.ACTION_APPEND).counter());
    }

    @Test
    @DisplayName("M4 失败：计入分子但不计分母，且异常照常上抛")
    void failure_countsNumeratorOnly() {
        doThrow(new IllegalStateException("redis down"))
                .when(feedTimelineDelivery).applyStrict(any());

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(FeedTimelineEvent.remove(POST_ID)));

        assertEquals(1.0, failed(FeedTimelineEvent.ACTION_REMOVE), 1e-9);
        // 失败不能同时计分母：否则失败率被自己的失败稀释，越坏越接近 0。
        assertEquals(0.0, total(FeedTimelineEvent.ACTION_REMOVE), 1e-9);
    }

    @Test
    @DisplayName("M4 消息体损坏：标签落 UNKNOWN，不产生 null 标签")
    void nullEvent_taggedUnknown() {
        doThrow(new IllegalArgumentException("bad payload")).when(feedTimelineDelivery).applyStrict(null);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(null));

        // Prometheus 里空标签会与「真的叫 null 的 action」混淆，规则也难以表达。
        assertNotNull(registry.find("tf_feed_timeline_consume_failed_total")
                .tag("action", "UNKNOWN").counter());
        assertEquals(1.0, failed("UNKNOWN"), 1e-9);
    }

    @Test
    @DisplayName("M4 APPEND 与 REMOVE 分开计数（REMOVE 失败更急，需单独配规则）")
    void appendAndRemoveCountedSeparately() {
        doThrow(new RuntimeException("x")).when(feedTimelineDelivery).applyStrict(any());

        assertThrows(RuntimeException.class,
                () -> consumer.onMessage(FeedTimelineEvent.append(item(), 1)));
        assertThrows(RuntimeException.class,
                () -> consumer.onMessage(FeedTimelineEvent.remove(POST_ID)));

        assertEquals(1.0, failed(FeedTimelineEvent.ACTION_APPEND), 1e-9);
        assertEquals(1.0, failed(FeedTimelineEvent.ACTION_REMOVE), 1e-9);
    }
}
