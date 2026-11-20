package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 DLQ 告警巡检逻辑护栏：阈值触发 + 冷却抑制 + 指标口径，纯 Mockito 无关（手动构造 store + 记录型 notifier），
 * 不依赖 Spring / 网络 / 字节码插桩，surefire 下稳定可跑。
 */
class DlqAlertServiceTest {

    private static FeedTimelineEvent event(String mediaId) {
        FeedItemView item = new FeedItemView(
                "post/" + mediaId, mediaId, "http://x/" + mediaId, null, "APPROVED",
                Instant.now(), "cap", null, null);
        return FeedTimelineEvent.append(item, 1);
    }

    private static DlqMessageStore storeWith(int n) {
        InMemoryDlqStore store = new InMemoryDlqStore();
        for (int i = 0; i < n; i++) {
            store.inject(event("m" + i));
        }
        return store;
    }

    /** 可切换为「broker 不可用」的 store，用来验证 Gauge 的 fail-quiet 口径。 */
    private static final class FlakyStore implements DlqMessageStore {
        private final DlqMessageStore delegate;
        private boolean fail;

        FlakyStore(DlqMessageStore delegate) {
            this.delegate = delegate;
        }

        void fail() {
            this.fail = true;
        }

        @Override
        public List<DlqMessage> peek() {
            if (fail) {
                throw new IllegalStateException("broker down");
            }
            return delegate.peek();
        }

        @Override
        public void acknowledge(DlqMessage msg) {
            delegate.acknowledge(msg);
        }
    }

    @Test
    void belowThreshold_doesNotAlert() {
        List<DlqAlert> alerts = new CopyOnWriteArrayList<>();
        DlqAlertService svc = new DlqAlertService(storeWith(1), alerts::add, 2, 60_000L, 20, "eng-1");
        svc.check();
        assertTrue(alerts.isEmpty(), "积压 < 阈值时不应发告警");
    }

    @Test
    void aboveThreshold_firesOnce_thenCooldownSuppresses() {
        List<DlqAlert> alerts = new CopyOnWriteArrayList<>();
        DlqAlertService svc = new DlqAlertService(storeWith(3), alerts::add, 2, 60_000L, 20, "eng-1");
        svc.check();
        svc.check(); // 冷却期内立即再查
        assertEquals(1, alerts.size(), "冷却期内重复巡检只应发一次告警");
        assertEquals(3, alerts.get(0).count());
    }

    @Test
    void cooldownElapsed_firesAgain() {
        List<DlqAlert> alerts = new CopyOnWriteArrayList<>();
        // cooldown=0 → 每次都满足冷却条件
        DlqAlertService svc = new DlqAlertService(storeWith(2), alerts::add, 2, 0L, 20, "eng-1");
        svc.check();
        svc.check();
        assertEquals(2, alerts.size(), "cooldown=0 时每次达阈值都应发告警");
    }

    @Test
    void sampleLimit_capsReportedMediaIds() {
        List<DlqAlert> alerts = new CopyOnWriteArrayList<>();
        DlqAlertService svc = new DlqAlertService(storeWith(10), alerts::add, 1, 0L, 3, "eng-1");
        svc.check();
        assertEquals(1, alerts.size());
        assertEquals(3, alerts.get(0).samples().size(), "样例 mediaId 不应超过 sample-limit");
    }

    @Test
    void metrics_depthGauge_isRegisteredAtZero_thenTracksPeekSize() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DlqAlertService svc = new DlqAlertService(storeWith(4), alerts -> { },
                2, 60_000L, 20, "eng-1", registry);

        assertEquals(0.0, registry.get("tf_feed_dlq_depth").gauge().value(), 1e-9,
                "启动即注册为 0，归零不应让时序消失");
        svc.check();
        assertEquals(4.0, registry.get("tf_feed_dlq_depth").gauge().value(), 1e-9,
                "Gauge 应反映最近一次 peek 的积压条数");
    }

    @Test
    void metrics_alertCounter_countsOnlyFiredAlerts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<DlqAlert> alerts = new CopyOnWriteArrayList<>();
        DlqAlertService svc = new DlqAlertService(storeWith(3), alerts::add,
                2, 60_000L, 20, "eng-1", registry);
        svc.check();
        svc.check(); // 冷却期内，不应再计数
        assertEquals(1, alerts.size());
        assertEquals(1.0, registry.get("tf_feed_dlq_alert_total").counter().count(), 1e-9,
                "告警计数应与实际触发次数一致，冷却期抑制不计");
    }

    @Test
    void metrics_peekFailure_keepsLastKnownDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlakyStore store = new FlakyStore(storeWith(5));
        DlqAlertService svc = new DlqAlertService(store, alerts -> { },
                2, 60_000L, 20, "eng-1", registry);
        svc.check();
        assertEquals(5.0, registry.get("tf_feed_dlq_depth").gauge().value(), 1e-9);

        store.fail();
        svc.check();
        assertEquals(5.0, registry.get("tf_feed_dlq_depth").gauge().value(), 1e-9,
                "巡检失败时保留上一轮值，掉 0 会被误读为积压已消化");
    }
}
