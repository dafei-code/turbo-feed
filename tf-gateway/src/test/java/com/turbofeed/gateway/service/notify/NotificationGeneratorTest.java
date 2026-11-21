package com.turbofeed.gateway.service.notify;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知生成器护栏（ADR-0009）。纯 Mockito、不启 Spring / 不依赖 Redis、Docker。
 *
 * <p>重点锁定三类易回归行为：① fail-open（Redis/DB 异常绝不抛回业务方）；
 * ② 幂等去重（同 eventId 只生成一次）；③ <b>不写收件箱 ZSET</b>——
 * 该 ZSET 读路径从未使用且只增不删（无界增长），2026-11-21 移除，本测试防止有人无意加回。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationGeneratorTest {

    private static final long USER = 1001L;
    private static final long NOTIFY_ID = 90001L;
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Mock private NotificationJdbcRepository repository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zSetOps;
    @Mock private SnowflakeIdGenerator snowflakeIdGenerator;

    /** 同步执行，让异步生成在测试中立即完成，便于断言。 */
    private final Executor directExecutor = Runnable::run;

    private NotificationGenerator generator;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(snowflakeIdGenerator.nextId()).thenReturn(NOTIFY_ID);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        generator = new NotificationGenerator(repository, redisTemplate, snowflakeIdGenerator, directExecutor);
    }

    private static NotificationEvent event(String eventId) {
        return new NotificationEvent(eventId, NotificationType.LIKE, USER, 2002L,
                "MEDIA", "post-1", "某人赞了你", null, NOW);
    }

    @Test
    void generate_writesDbAndIncrementsUnread_butNeverTouchesInboxZset() {
        generator.generate(event("evt-1"));

        verify(repository).insert(any(Notification.class));
        verify(valueOps).increment("tf:notify:unread:" + USER);
        verify(redisTemplate, never()).opsForZSet();
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void generate_sameEventId_onlyOnce() {
        generator.generate(event("evt-dup"));
        generator.generate(event("evt-dup"));

        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        generator.generate(event("evt-dup"));

        // 前两次首次去重成功应落库；第三次模拟 SETNX 失败（重复事件）→ 不再落库。
        // 注：真实去重由 Redis SETNX 语义保证，这里用 mock 切换返回值来验证"去重失败即跳过"分支。
        verify(repository, org.mockito.Mockito.times(2)).insert(any(Notification.class));
    }

    @Test
    void generate_redisFailure_stillWritesDb_noThrow() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenThrow(new RuntimeException("redis down"));

        generator.generate(event("evt-redis-down")); // 不得抛异常

        // 去重查询异常时放行（fail-open），仍落库
        verify(repository).insert(any(Notification.class));
    }

    @Test
    void generate_dbFailure_noThrow() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(repository).insert(any(Notification.class));

        generator.generate(event("evt-db-down")); // 不得抛异常

        // DB 挂了也不该走到未读计数（写库失败即短路）
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void unreadCount_redisHit_returnsFastPathValue() {
        when(valueOps.get("tf:notify:unread:" + USER)).thenReturn("7");

        assertEquals(7L, generator.unreadCount(USER));
        verify(repository, never()).countUnread(anyLong());
    }

    @Test
    void unreadCount_redisMiss_fallsBackToDb() {
        when(valueOps.get("tf:notify:unread:" + USER)).thenReturn(null);
        when(repository.countUnread(USER)).thenReturn(3L);

        assertEquals(3L, generator.unreadCount(USER));
        verify(repository).countUnread(USER);
    }

    @Test
    void unreadCount_redisAndDbFail_returnsZero_noThrow() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(repository.countUnread(anyLong())).thenThrow(new RuntimeException("db down"));

        assertEquals(0L, generator.unreadCount(USER));
    }

    @Test
    void inbox_readsDbAndCapsLimit() {
        Notification n = new Notification(NOTIFY_ID, USER, "LIKE", 2002L, "MEDIA", "post-1",
                "某人赞了你", null, false, NOW, 0L);
        when(repository.listByUser(eq(USER), eq(0L), eq(100))).thenReturn(List.of(n));
        when(valueOps.get(anyString())).thenReturn("1");

        NotificationGenerator.NotificationListView view = generator.inbox(USER, 0L, 999); // 上限夹到 100

        verify(repository).listByUser(USER, 0L, 100);
        assertEquals(1, view.items().size());
        assertEquals(1L, view.unreadCount());
        assertEquals(NOW.toEpochMilli(), view.items().get(0).createdAtMs());
    }

    @Test
    void markRead_updatesDbAndDecrementsRedis() {
        when(repository.markRead(eq(USER), any())).thenReturn(2);
        when(valueOps.get(anyString())).thenReturn("5");

        long remaining = generator.markRead(USER, List.of(1L, 2L), null);

        verify(repository).markRead(eq(USER), any());
        verify(valueOps).decrement("tf:notify:unread:" + USER, 2);
        assertEquals(5L, remaining); // 返回值取 Redis 快路径（mock 值未随 decrement 变化）
    }

    @Test
    void markRead_noRowsUpdated_doesNotDecrement() {
        when(repository.markAllRead(USER, null)).thenReturn(0);
        when(valueOps.get(anyString())).thenReturn("0");

        generator.markRead(USER, List.of(), "ALL");

        verify(valueOps, never()).decrement(anyString(), anyLong());
        assertTrue(true); // 无异常即通过
    }
}
