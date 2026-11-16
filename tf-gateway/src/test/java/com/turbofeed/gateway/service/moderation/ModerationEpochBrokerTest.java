package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModerationEpochBroker} 单测：用假 Transport 模拟两个网关实例与 Redis，
 * 不依赖 Spring / Redis，覆盖跨实例收敛的核心判定。
 *
 * <p>重点不是「Redis 能不能发出消息」（那是集成测试的事），而是
 * <b>epoch 比较的纪律</b>：只接受严格更大的版本、自广播跳过、回调缺失不吞消息、
 * 全局 epoch 回退可自愈、任何 Redis 故障都不向 admin 写路径抛异常。</p>
 */
class ModerationEpochBrokerTest {

    /** 共享的假 Redis：两个 broker 实例共用同一份 epoch 值，模拟同一 Redis。 */
    private static final class FakeTransport implements ModerationEpochBroker.Transport {

        final Map<String, Long> values = new HashMap<>();
        final List<String> published = new ArrayList<>();
        boolean failIncrement;
        boolean failPublish;
        boolean failGet;

        @Override
        public long increment(String key) {
            if (failIncrement) {
                throw new IllegalStateException("redis down");
            }
            long next = values.getOrDefault(key, 0L) + 1;
            values.put(key, next);
            return next;
        }

        @Override
        public long get(String key) {
            if (failGet) {
                throw new IllegalStateException("redis down");
            }
            return values.getOrDefault(key, -1L);
        }

        @Override
        public void publish(String channel, String payload) {
            if (failPublish) {
                throw new IllegalStateException("publish down");
            }
            published.add(channel + "|" + payload);
        }
    }

    private static ModerationEpochBroker broker(FakeTransport t, String instanceId) {
        return new ModerationEpochBroker(t, true, instanceId);
    }

    @Test
    void disabled_noRedisTrafficAndNoRebuild() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = new ModerationEpochBroker(t, false, "A");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        assertEquals(-1L, b.bump(ModerationEpochBroker.Kind.DICT));
        b.onRemoteMessage("B:DICT:9");
        assertFalse(b.ensureFresh(ModerationEpochBroker.Kind.DICT));
        b.alignBaselineOnStartup();

        assertEquals(0, calls.get());
        assertTrue(t.values.isEmpty(), "开关关闭时不得产生任何 Redis 写入");
        assertTrue(t.published.isEmpty());
    }

    @Test
    void bump_incrementsPerKindAndPublishesExpectedPayload() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = broker(t, "A");

        assertEquals(1L, b.bump(ModerationEpochBroker.Kind.DICT));
        assertEquals(1L, b.bump(ModerationEpochBroker.Kind.WHITELIST));
        assertEquals(2L, b.bump(ModerationEpochBroker.Kind.DICT));

        assertEquals(List.of(
                ModerationEpochBroker.CHANNEL + "|A:DICT:1",
                ModerationEpochBroker.CHANNEL + "|A:WHITELIST:1",
                ModerationEpochBroker.CHANNEL + "|A:DICT:2"), t.published);
        assertEquals(2L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
        assertEquals(1L, b.currentEpoch(ModerationEpochBroker.Kind.WHITELIST));
    }

    @Test
    void selfMessage_isIgnored() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker a = broker(t, "A");
        AtomicInteger calls = new AtomicInteger();
        a.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        a.bump(ModerationEpochBroker.Kind.DICT);
        a.onRemoteMessage("A:DICT:1");

        assertEquals(0, calls.get(), "自广播必须跳过，否则多实例会形成回环");
    }

    @Test
    void remoteMessage_triggersOnlyForStrictlyNewerEpoch() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        b.onRemoteMessage("A:DICT:5");
        assertEquals(1, calls.get());
        assertEquals(5L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));

        b.onRemoteMessage("A:DICT:5"); // 重复
        b.onRemoteMessage("A:DICT:3"); // 迟到 / 乱序
        assertEquals(1, calls.get(), "重复与旧消息不得触发重建，更不得把基线拉回去");
        assertEquals(5L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));

        b.onRemoteMessage("A:DICT:6");
        assertEquals(2, calls.get());
        assertEquals(6L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
    }

    @Test
    void malformedMessages_areIgnoredWithoutChangingEpoch() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        for (String bad : List.of("", "  ", "abc", "A", "A:UNKNOWN:1", "A:DICT:oops", "A:DICT:0", "A:DICT:-3")) {
            b.onRemoteMessage(bad);
        }
        b.onRemoteMessage(null);

        assertEquals(0, calls.get());
        assertEquals(0L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
    }

    @Test
    void missingTrigger_keepsLagging_forFallback() {
        FakeTransport t = new FakeTransport();
        t.values.put(ModerationEpochBroker.DICT_EPOCH_KEY, 7L); // 广播方已 INCR 过
        ModerationEpochBroker b = broker(t, "B");

        b.onRemoteMessage("A:DICT:7");
        assertEquals(0L, b.currentEpoch(ModerationEpochBroker.Kind.DICT),
                "回调未注册时不能推进基线，否则兜底会误以为已追平而吞掉这次变更");

        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);
        assertTrue(b.ensureFresh(ModerationEpochBroker.Kind.DICT));
        assertEquals(1, calls.get());
        assertEquals(7L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
        assertFalse(b.ensureFresh(ModerationEpochBroker.Kind.DICT), "已追平后不得重复重建");
    }

    @Test
    void publishFailure_isFailOpen_andFallbackStillConverges() {
        FakeTransport t = new FakeTransport();
        t.failPublish = true;
        ModerationEpochBroker a = broker(t, "A");
        assertEquals(1L, a.bump(ModerationEpochBroker.Kind.DICT), "广播失败不得影响 admin 写请求");
        assertTrue(t.published.isEmpty());

        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);
        assertTrue(b.ensureFresh(ModerationEpochBroker.Kind.DICT), "epoch 已推进，30s 兜底应补齐丢失的广播");
        assertEquals(1, calls.get());
    }

    @Test
    void incrementFailure_isFailOpen() {
        FakeTransport t = new FakeTransport();
        t.failIncrement = true;
        ModerationEpochBroker a = broker(t, "A");

        assertEquals(-1L, a.bump(ModerationEpochBroker.Kind.DICT));
        assertTrue(t.published.isEmpty());
        assertEquals(0L, a.currentEpoch(ModerationEpochBroker.Kind.DICT));
    }

    @Test
    void getFailure_doesNotThrow_andDegradesToChecksumTimer() {
        FakeTransport t = new FakeTransport();
        t.failGet = true;
        ModerationEpochBroker b = broker(t, "B");
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, () -> {
        });

        assertFalse(b.ensureFresh(ModerationEpochBroker.Kind.DICT));
        b.alignBaselineOnStartup(); // 启动期读 epoch 失败也不得影响启动
    }

    @Test
    void epochRollback_isFollowedByFallback_andLaterBroadcastWorksAgain() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        b.onRemoteMessage("A:DICT:10");
        assertEquals(10L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));

        // 模拟 Redis 被 FLUSHDB 后 epoch 从 1 重新开始
        t.values.put(ModerationEpochBroker.DICT_EPOCH_KEY, 1L);
        assertFalse(b.ensureFresh(ModerationEpochBroker.Kind.DICT));
        assertEquals(1L, b.currentEpoch(ModerationEpochBroker.Kind.DICT), "本地基线应跟随全局回退，否则新广播永远被当旧消息");

        assertEquals(2L, b.bump(ModerationEpochBroker.Kind.DICT));
        ModerationEpochBroker c = broker(t, "C");
        AtomicInteger cCalls = new AtomicInteger();
        c.registerTrigger(ModerationEpochBroker.Kind.DICT, cCalls::incrementAndGet);
        c.onRemoteMessage("B:DICT:2");
        assertEquals(1, cCalls.get(), "回退后的新一轮广播必须重新生效");
    }

    @Test
    void alignBaselineOnStartup_syncsWithoutRebuild() {
        FakeTransport t = new FakeTransport();
        t.values.put(ModerationEpochBroker.DICT_EPOCH_KEY, 9L);
        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger calls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, calls::incrementAndGet);

        b.alignBaselineOnStartup();
        assertEquals(9L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
        assertEquals(0, calls.get(), "启动时已同步加载 DB，不该因历史 epoch 白白重建一次");
        assertFalse(b.ensureFresh(ModerationEpochBroker.Kind.DICT));
    }

    @Test
    void kindsAreIndependent() {
        FakeTransport t = new FakeTransport();
        ModerationEpochBroker b = broker(t, "B");
        AtomicInteger dictCalls = new AtomicInteger();
        AtomicInteger whitelistCalls = new AtomicInteger();
        b.registerTrigger(ModerationEpochBroker.Kind.DICT, dictCalls::incrementAndGet);
        b.registerTrigger(ModerationEpochBroker.Kind.WHITELIST, whitelistCalls::incrementAndGet);

        b.onRemoteMessage("A:WHITELIST:3");

        assertEquals(0, dictCalls.get(), "改白名单不该让词库全量重建");
        assertEquals(1, whitelistCalls.get());
        assertEquals(0L, b.currentEpoch(ModerationEpochBroker.Kind.DICT));
        assertEquals(3L, b.currentEpoch(ModerationEpochBroker.Kind.WHITELIST));
    }
}
