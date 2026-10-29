package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 DLQ 告警巡检逻辑护栏：阈值触发 + 冷却抑制，纯 Mockito 无关（手动构造 store + 记录型 notifier），
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
}
