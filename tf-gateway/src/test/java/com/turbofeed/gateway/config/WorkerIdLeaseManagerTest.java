package com.turbofeed.gateway.config;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * worker-id 自租约单测（M2 / 4.3）。
 *
 * <p>纯 Mockito，不连 Redis。<b>真正的风险不在这几行代码，而在它的失效模式</b>：
 * 租约的意义是「保证同一时刻只有一个实例持有某个 worker-id」，一旦失效，两个实例会在
 * 同一毫秒生成完全相同的主键——<b>不报错在发号那一刻</b>，而是表现为线上偶发的
 * {@code DuplicateKeyException}（注册失败、评论失败）。所以这里重点测的是「什么时候应该
 * 拒绝出 ID」，而不是「正常路径能不能跑通」。</p>
 */
class WorkerIdLeaseManagerTest {

    private StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> values = mock(ValueOperations.class);
    private WorkerIdLeaseManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.release();
        }
    }

    @SuppressWarnings("unchecked")
    private WorkerIdLeaseManager newManager(long ttl, long renewInterval) {
        when(redis.opsForValue()).thenReturn(values);
        manager = new WorkerIdLeaseManager(redis, 1L, ttl, renewInterval);
        return manager;
    }

    private void stubAcquire(int freeFrom) {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    int workerId = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
                    return workerId >= freeFrom;
                });
    }

    // ==================== 抢占 ====================

    @Test
    void acquire_抢到空闲号并在TTL内占位() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);

        int workerId = m.acquire();

        assertThat(workerId).isBetween(0, WorkerIdLeaseManager.MAX_WORKER_ID);
        assertThat(m.isHeld()).isTrue();
        verify(values).setIfAbsent("tf:snowflake:lease:1:" + workerId, m.instanceId(), 60L, TimeUnit.SECONDS);
    }

    @Test
    void acquire_低位被占则顺延到下一个空闲号() {
        // 0~4 已被占，从 5 开始才空闲
        stubAcquire(5);
        WorkerIdLeaseManager m = newManager(60, 20);

        assertThat(m.acquire()).isGreaterThanOrEqualTo(5);
        assertThat(m.isHeld()).isTrue();
    }

    @Test
    void acquire_全部被占满则启动失败() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> newManager(60, 20).acquire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("全部被占用");
    }

    @Test
    void acquire_Redis不可用时启动失败并给出两条出路() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> newManager(60, 20).acquire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis 不可用")
                .hasMessageContaining("lease-enabled");   // 必须给出「改用显式配置」这条出路
    }

    @Test
    void acquire_续约间隔超过TTL一半则启动失败() {
        // 续约间隔必须显著小于 TTL，否则「续约还没发生租约就过期」，
        // 中间窗口里 worker-id 会被别的实例抢走而本进程还在发号
        assertThatThrownBy(() -> newManager(30, 20).acquire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("的一半");
        assertThatThrownBy(() -> newManager(0, 10).acquire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ttl-seconds 必须为正数");
    }

    // ==================== 续约与拉闸（fail-closed） ====================

    @Test
    void renew_成功则保持持有() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        stubScriptResult(1L);

        m.renew();

        assertThat(m.isHeld()).isTrue();
    }

    @Test
    void renew_租约被别人抢占则拉闸() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        stubScriptResult(0L);   // Lua 比对值失败：租约已不属于本实例

        m.renew();

        assertThat(m.isHeld()).isFalse();
        // 关键连带效应：闸门一关，生成器必须拒绝出 ID（否则就是撞主键）
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(m.workerId(), m.datacenterId(), m);
        assertThatThrownBy(gen::nextId).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约已失效");
    }

    @Test
    void renew_异常同样拉闸() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new IllegalStateException("redis down"));

        m.renew();

        assertThat(m.isHeld()).isFalse();
    }

    @Test
    void renew_恢复后重新持有() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        stubScriptResult(0L);
        m.renew();
        assertThat(m.isHeld()).isFalse();

        // Redis 恢复后下一轮续约应重新拉起闸门，无需重启
        stubScriptResult(1L);
        m.renew();

        assertThat(m.isHeld()).isTrue();
    }

    @Test
    void rent_未持有时不查Redis() {
        WorkerIdLeaseManager m = newManager(60, 20);
        m.renew();
        verifyNoScriptExecuted();
    }

    // ==================== 释放与闸门边界 ====================

    @Test
    void release_优雅停机释放租约且用Lua比对值() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        stubScriptResult(1L);

        m.release();

        assertThat(m.isHeld()).isFalse();
        // 必须是「比对 instanceId 后删除」的 Lua，不能是无脑 DEL（那会删掉别人的租约）
        verify(redis).execute(any(RedisScript.class), eq(List.of("tf:snowflake:lease:1:" + m.workerId())),
                eq(m.instanceId()));
    }

    @Test
    void release_释放失败不影响停机() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new IllegalStateException("redis down"));

        m.release();   // 不得抛出：TTL 兜底会让租约自然过期

        assertThat(m.isHeld()).isFalse();
    }

    @Test
    void release_未抢占时不调用Redis() {
        WorkerIdLeaseManager m = newManager(60, 20);
        m.release();
        verifyNoScriptExecuted();
    }

    /** 未启用租约（显式配置模式）时，生成器不应被任何闸门拦住。 */
    @Test
    void 生成器在无闸门时可正常出号() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(3, 1);
        assertThat(gen.nextId()).isGreaterThan(0L);
        assertThat(gen.nextId()).isGreaterThan(0L);
    }

    @Test
    void 闸门持有中可正常出号() {
        stubAcquire(0);
        WorkerIdLeaseManager m = newManager(60, 20);
        m.acquire();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(m.workerId(), m.datacenterId(), m);

        assertThat(gen.nextId()).isGreaterThan(0L);
        assertThat(m.isHeld()).isTrue();
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private void stubScriptResult(long result) {
        // ⚠️ Mockito 对 varargs 按【实参个数】分别匹配，两种形状都要打桩：
        //    renew   → execute(script, keys, instanceId, ttl)   两个 ARGV
        //    release → execute(script, keys, instanceId)        一个 ARGV
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(result);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(result);
    }

    /** 断言一次 Lua 都没执行（未抢占 / 未持有时不得碰 Redis）。 */
    private void verifyNoScriptExecuted() {
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }
}
