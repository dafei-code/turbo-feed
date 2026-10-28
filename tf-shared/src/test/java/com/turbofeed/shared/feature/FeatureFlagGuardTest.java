package com.turbofeed.shared.feature;

import com.turbofeed.shared.feature.FeatureFlagGuard.PropertyLookup;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FeatureFlagGuard} 隔离单测（纯函数、零中间件依赖）。
 *
 * <p>覆盖 6 类危险开关组合的全部分支：MQ 总闸 / 死信 replay 依赖 / Redis 拓扑 /
 * 计数缓冲依赖 / 向量召回模式 / 媒资存储取值。同时顺带核验 {@link FeatureFlagKeys}
 * 常量值与预期一致（给常量类本身也带上覆盖率）。</p>
 *
 * <p>守卫是启动期 fail-fast 的最后一道防线，必须保证「默认配置零影响、危险组合精确报错」，
 * 故这里把每一条 fail 路径都显式断言。</p>
 */
class FeatureFlagGuardTest {

    // ===== 工具：用 Map 构造属性读取器 =====

    private PropertyLookup lookup(Map<String, String> props) {
        return props::get;
    }

    private void assertPasses(Map<String, String> props, String... profiles) {
        new FeatureFlagGuard(lookup(props), profiles).validate();
    }

    private void assertFails(Map<String, String> props, String... profiles) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FeatureFlagGuard(lookup(props), profiles).validate());
        assertTrue(ex.getMessage().contains("[FeatureFlagGuard]"),
                "报错应带 [FeatureFlagGuard] 前缀，实际：" + ex.getMessage());
    }

    // ===== MQ 总闸 =====

    @Test
    void mqDisabled_passes() {
        assertPasses(Map.of(FeatureFlagKeys.MQ_ENABLED, "false"));
    }

    @Test
    void mqEnabled_noNameServer_noProfile_fails() {
        assertFails(Map.of(FeatureFlagKeys.MQ_ENABLED, "true"));
    }

    @Test
    void mqEnabled_nameServer_andMqProfile_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.MQ_ENABLED, "true");
        p.put(FeatureFlagKeys.ROCKETMQ_NAME_SERVER, "127.0.0.1:9876");
        assertPasses(p, "mq");
    }

    @Test
    void mqEnabled_nameServer_withoutMqProfile_fails() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.MQ_ENABLED, "true");
        p.put(FeatureFlagKeys.ROCKETMQ_NAME_SERVER, "127.0.0.1:9876");
        assertFails(p);
    }

    @Test
    void mqEnabled_mqProfile_withoutNameServer_fails() {
        assertFails(Map.of(FeatureFlagKeys.MQ_ENABLED, "true"), "mq");
    }

    // ===== 死信 replay 依赖 MQ =====

    @Test
    void dlqReplayEnabled_withoutMq_fails() {
        assertFails(Map.of(FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED, "true"));
    }

    @Test
    void dlqReplayEnabled_withMq_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED, "true");
        p.put(FeatureFlagKeys.MQ_ENABLED, "true");
        p.put(FeatureFlagKeys.ROCKETMQ_NAME_SERVER, "127.0.0.1:9876");
        assertPasses(p, "mq");
    }

    @Test
    void dlqReplayDisabled_passes() {
        assertPasses(Map.of(FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED, "false"));
    }

    // ===== Redis 拓扑 =====

    @Test
    void redisMode_absent_defaultsToSingle_passes() {
        assertPasses(Map.of());
    }

    @Test
    void redisMode_single_passes() {
        assertPasses(Map.of(FeatureFlagKeys.REDIS_MODE, "single"));
    }

    @Test
    void redisMode_cluster_withNodes_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.REDIS_MODE, "cluster");
        p.put(FeatureFlagKeys.REDIS_CLUSTER_NODES, "127.0.0.1:7000,127.0.0.1:7001");
        assertPasses(p);
    }

    @Test
    void redisMode_cluster_withoutNodes_fails() {
        assertFails(Map.of(FeatureFlagKeys.REDIS_MODE, "cluster"));
    }

    @Test
    void redisMode_sentinel_withMaster_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.REDIS_MODE, "sentinel");
        p.put(FeatureFlagKeys.REDIS_SENTINEL_MASTER, "mymaster");
        assertPasses(p);
    }

    @Test
    void redisMode_sentinel_withoutMaster_fails() {
        assertFails(Map.of(FeatureFlagKeys.REDIS_MODE, "sentinel"));
    }

    @Test
    void redisMode_illegal_fails() {
        assertFails(Map.of(FeatureFlagKeys.REDIS_MODE, "weird"));
    }

    // ===== 计数缓冲依赖 Redis =====

    @Test
    void counterBuffer_disabled_passes() {
        assertPasses(Map.of(FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED, "false"));
    }

    @Test
    void counterBuffer_enabled_withRedis_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED, "true");
        p.put(FeatureFlagKeys.COUNTER_REDIS_ENABLED, "true");
        assertPasses(p);
    }

    @Test
    void counterBuffer_enabled_withoutRedis_fails() {
        assertFails(Map.of(FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED, "true"));
    }

    // ===== 向量召回模式 =====

    @Test
    void vectorMode_hash_passes() {
        assertPasses(Map.of(FeatureFlagKeys.VECTOR_MODE, "hash"));
    }

    @Test
    void vectorMode_model_passes() {
        assertPasses(Map.of(FeatureFlagKeys.VECTOR_MODE, "model"));
    }

    @Test
    void vectorMode_ann_passes() {
        assertPasses(Map.of(FeatureFlagKeys.VECTOR_MODE, "ann"));
    }

    @Test
    void vectorMode_illegal_fails() {
        assertFails(Map.of(FeatureFlagKeys.VECTOR_MODE, "xyz"));
    }

    @Test
    void twoTower_enabled_withHashMode_fails() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.VECTOR_TWO_TOWER_ENABLED, "true");
        p.put(FeatureFlagKeys.VECTOR_MODE, "hash");
        assertFails(p);
    }

    @Test
    void twoTower_enabled_withModelMode_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.VECTOR_TWO_TOWER_ENABLED, "true");
        p.put(FeatureFlagKeys.VECTOR_MODE, "model");
        assertPasses(p);
    }

    @Test
    void twoTower_enabled_withAnnMode_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.VECTOR_TWO_TOWER_ENABLED, "true");
        p.put(FeatureFlagKeys.VECTOR_MODE, "ann");
        assertPasses(p);
    }

    @Test
    void twoTower_disabled_withHashMode_passes() {
        Map<String, String> p = new HashMap<>();
        p.put(FeatureFlagKeys.VECTOR_TWO_TOWER_ENABLED, "false");
        p.put(FeatureFlagKeys.VECTOR_MODE, "hash");
        assertPasses(p);
    }

    // ===== 媒资存储取值 =====

    @Test
    void mediaStorage_absent_passes() {
        assertPasses(Map.of());
    }

    @Test
    void mediaStorage_validValues_passes() {
        for (String v : new String[]{"minio", "local", "placeholder"}) {
            assertPasses(Map.of(FeatureFlagKeys.MEDIA_STORAGE, v));
        }
    }

    @Test
    void mediaStorage_illegal_fails() {
        assertFails(Map.of(FeatureFlagKeys.MEDIA_STORAGE, "s3"));
    }

    // ===== FeatureFlagKeys 常量值核验（带覆盖率）=====

    @Test
    void featureFlagKeys_matchExpected() {
        assertEquals("turbofeed.mq.enabled", FeatureFlagKeys.MQ_ENABLED);
        assertEquals("rocketmq.name-server", FeatureFlagKeys.ROCKETMQ_NAME_SERVER);
        assertEquals("turbofeed.feed.timeline.dlq-replay.enabled", FeatureFlagKeys.FEED_TIMELINE_DLQ_REPLAY_ENABLED);
        assertEquals("turbofeed.feed.recall.vector.mode", FeatureFlagKeys.VECTOR_MODE);
        assertEquals("turbofeed.feed.recall.vector.two-tower.enabled", FeatureFlagKeys.VECTOR_TWO_TOWER_ENABLED);
        // 关键：真实 Redis key 是连字符 cluster-nodes（非点号），否则 cluster 合法配置会被误杀
        assertEquals("turbofeed.redis.cluster-nodes", FeatureFlagKeys.REDIS_CLUSTER_NODES);
        assertEquals("turbofeed.redis.mode", FeatureFlagKeys.REDIS_MODE);
        assertEquals("turbofeed.redis.redisson-enabled", FeatureFlagKeys.REDIS_REDISSON_ENABLED);
        assertEquals("turbofeed.redis.sentinel-master", FeatureFlagKeys.REDIS_SENTINEL_MASTER);
        assertEquals("turbofeed.redis.sentinel-nodes", FeatureFlagKeys.REDIS_SENTINEL_NODES);
        assertEquals("turbofeed.redis.max-redirects", FeatureFlagKeys.REDIS_MAX_REDIRECTS);
        assertEquals("turbofeed.counter.redis-enabled", FeatureFlagKeys.COUNTER_REDIS_ENABLED);
        assertEquals("turbofeed.counter.redis-buffer-enabled", FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED);
        assertEquals("turbofeed.counter.service.enabled", FeatureFlagKeys.COUNTER_SERVICE_ENABLED);
        assertEquals("turbofeed.counter.service.topic", FeatureFlagKeys.COUNTER_SERVICE_TOPIC);
        assertEquals("turbofeed.media.storage", FeatureFlagKeys.MEDIA_STORAGE);
        assertEquals("turbofeed.state-cache.broadcast-enabled", FeatureFlagKeys.STATE_CACHE_BROADCAST_ENABLED);
        assertEquals("turbofeed.snowflake.lease-enabled", FeatureFlagKeys.SNOWFLAKE_LEASE_ENABLED);
        assertEquals("turbofeed.sentinel.nacos.enabled", FeatureFlagKeys.SENTINEL_NACOS_ENABLED);
        assertEquals("turbofeed.outbox.relay-enabled", FeatureFlagKeys.OUTBOX_RELAY_ENABLED);
        assertEquals("turbofeed.outbox.backlog-metrics-enabled", FeatureFlagKeys.OUTBOX_BACKLOG_METRICS_ENABLED);
        assertEquals("turbofeed.search.rebuild.offline.ds0-url", FeatureFlagKeys.SEARCH_REBUILD_OFFLINE_DS0_URL);
    }
}
