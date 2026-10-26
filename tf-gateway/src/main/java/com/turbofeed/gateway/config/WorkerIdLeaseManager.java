package com.turbofeed.gateway.config;

import com.turbofeed.gateway.util.IdLease;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * worker-id 的<b>自租约</b>分配与保活（M2 / 4.3，R9）。
 *
 * <p><b>它解决什么</b>：雪花 ID 由 {@code (时间戳, datacenterId, workerId, sequence)} 拼成，
 * 两个实例共用同一 worker-id 时<b>同毫秒必然生成相同主键</b>。改造前唯一防护是「部署时逐实例
 * 人肉分配环境变量」——这正是最不可靠的一类约定：漏配不报错（旧实现还会静默兜底成 (1,1)），
 * 扩容加机器时最容易忘。自租约把「分配」这件事交给 Redis：启动时 {@code SET key val NX EX}
 * 抢一个没人占的号，运行期定期续约，停机时释放。</p>
 *
 * <p><b>取值范围</b>：worker-id 只有 5 bit（0~31），因此同一个 datacenter 下<b>最多 32 个实例</b>。
 * 占满即启动失败（明确报错），不静默复用别人的号。</p>
 *
 * <p><b>三条安全边界</b>：</p>
 * <ol>
 *   <li><b>抢占用 {@code SET NX EX} 原子操作</b>，不用「先 GET 再 SET」——后者在并发启动时
 *       两个实例会读到同一个空闲号，双双认为抢到了；</li>
 *   <li><b>续约与释放都用 Lua 比对 instanceId</b>：租约可能已过期并被别人抢走，
 *       若不看值直接 {@code EXPIRE}/{@code DEL}，等于替别人的租约续期、或把别人的租约删掉；</li>
 *   <li><b>续约失败 = 停止发号</b>（{@link #isHeld()} 置 false，生成器拒绝出 ID）。
 *       见 {@link IdLease} 的 fail-closed 说明。</li>
 * </ol>
 *
 * <p><b>与「显式配置」模式的关系（二选一，不混合）</b>：本类由
 * {@link SnowflakeConfig#workerIdLeaseManager} 在
 * {@code turbofeed.snowflake.lease-enabled=true} 时装配（<b>刻意不用 {@code @Component}</b>——
 * 抢占必须发生在 Bean 方法内而不是 {@code @PostConstruct}，否则依赖它的生成器取不到
 * worker-id；类上再加 {@code @Component} 会与方法级 {@code @Bean} 同名重复注册）。
 * 关闭时走显式配置路径（worker-id 必填、缺失即启动失败）。<b>两条路都不允许静默重复</b>——
 * 这正是 M0「凭据缺失即拒绝启动」的同一条守则。</p>
 *
 * <p>⚠️ <b>与升级方案 4.3 的一处刻意偏离</b>：方案原文写「Redis 不可用时退化为『必须显式配置
 * 否则拒启动』」，即 Redis 挂了还能靠显式配置起来。本实现选择<b>直接启动失败</b>，理由是：
 * 退化路径会让「这个实例究竟有没有租约保护」变得不确定——运维看到启动成功，却不知道它跑在
 * 人肉分配的号上；而撞主键是资损级故障。报错信息里直接给出两条出路（修 Redis，或关掉
 * {@code lease-enabled} 改用显式配置），符合 M0 的「拒止式安全」基调：宁可起不来，不要起错。</p>
 */
@Slf4j
public class WorkerIdLeaseManager implements IdLease {

    /** worker-id 位数上限（5 bit，与 {@code SnowflakeIdGenerator} 一致）。 */
    static final int MAX_WORKER_ID = 31;

    /** 租约 key：{@code tf:snowflake:lease:{datacenterId}:{workerId}}。 */
    private static final String KEY_PREFIX = "tf:snowflake:lease:";

    /**
     * 续约 Lua：仅当当前值仍是本实例的 instanceId 才续期。
     *
     * <p><b>为什么要比对值</b>：租约可能已过期并被别的实例抢走。若不看值直接 EXPIRE，
     * 就等于替别人续了租——两个实例会同时认为号是自己的，撞回原点。</p>
     */
    private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1 else return 0 end",
            Long.class);

    /** 释放 Lua：同理，只删自己持有的租约。 */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('DEL', KEYS[1]) return 1 else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final long datacenterId;
    private final long ttlSeconds;
    private final long renewIntervalSeconds;
    private final String instanceId;

    private volatile int workerId = -1;
    private volatile String leaseKey;
    /** 续约是否仍成功：由续约线程写、由 {@link #isHeld()} 读（每次 nextId 都会读）。 */
    private final AtomicBoolean held = new AtomicBoolean(false);

    private ScheduledExecutorService renewScheduler;

    public WorkerIdLeaseManager(StringRedisTemplate redis,
                                @Value("${turbofeed.snowflake.datacenter-id}") long datacenterId,
                                @Value("${turbofeed.snowflake.lease-ttl-seconds:60}") long ttlSeconds,
                                @Value("${turbofeed.snowflake.lease-renew-interval-seconds:20}") long renewIntervalSeconds) {
        this.redis = redis;
        this.datacenterId = datacenterId;
        this.ttlSeconds = ttlSeconds;
        this.renewIntervalSeconds = renewIntervalSeconds;
        // 进程唯一标识：JVM 的 "pid@hostname"，足以区分同机多实例，无需引入新依赖
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName();
    }

    /**
     * 抢占一个空闲 worker-id 并启动续约线程。
     *
     * <p><b>为什么显式调用而不是 {@code @PostConstruct}</b>：依赖本管理器的
     * {@code SnowflakeIdGenerator} Bean 需要在构造时就拿到 worker-id，而
     * {@code @PostConstruct} 要等本 Bean 返回后才执行，时序上取不到。显式调用让
     * 「抢占失败即启动失败」这件事在调用栈里一眼可见。</p>
     *
     * @return 抢到的 worker-id（0~31）
     */
    public int acquire() {
        validateIntervals();
        // 随机起点：避免所有实例同时启动都从 0 开始试，把冲突集中到低位号上
        int start = (int) (Math.random() * (MAX_WORKER_ID + 1));
        for (int i = 0; i <= MAX_WORKER_ID; i++) {
            int candidate = (start + i) % (MAX_WORKER_ID + 1);
            String key = KEY_PREFIX + datacenterId + ":" + candidate;
            try {
                Boolean ok = redis.opsForValue()
                        .setIfAbsent(key, instanceId, ttlSeconds, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    this.workerId = candidate;
                    this.leaseKey = key;
                    this.held.set(true);
                    log.info("worker-id 租约抢占成功: workerId={}, datacenterId={}, key={}, ttl={}s, instanceId={}",
                            candidate, datacenterId, key, ttlSeconds, instanceId);
                    startRenewing();
                    return candidate;
                }
            } catch (Exception e) {
                // Redis 不可达：直接启动失败，不做任何静默退化（理由见类注释的「刻意偏离」）
                throw new IllegalStateException(
                        "worker-id 自租约启用（turbofeed.snowflake.lease-enabled=true）但 Redis 不可用，拒绝启动。"
                                + "两条出路：① 修复 Redis 连通性；② 关闭 lease-enabled，改用显式配置"
                                + " turbofeed.snowflake.worker-id（多实例必须逐实例不同）。", e);
            }
        }
        throw new IllegalStateException("worker-id 租约抢占失败：0~" + MAX_WORKER_ID
                + " 全部被占用（datacenterId=" + datacenterId + "）。"
                + "雪花 worker-id 只有 5 bit，同一机房最多 32 个实例；请扩容 datacenter 维度或清理僵尸租约。");
    }

    /**
     * 优雅停机时释放租约，让重启能立刻复用同一个号（否则要等 TTL 过期）。
     *
     * <p>释放失败不影响停机：TTL 兜底会让租约自然过期，只是期间这个号暂时不可用。</p>
     */
    public void release() {
        if (renewScheduler != null) {
            renewScheduler.shutdownNow();
        }
        String key = this.leaseKey;
        if (key == null) {
            return;
        }
        try {
            Long n = redis.execute(RELEASE_SCRIPT, List.of(key), instanceId);
            log.info("worker-id 租约释放: workerId={}, released={}", workerId, n);
        } catch (Exception e) {
            log.warn("worker-id 租约释放失败（不影响停机，TTL {}s 后自然过期）: {}", ttlSeconds, e.getMessage());
        }
        held.set(false);
    }

    /** 抢到的 worker-id（{@link #acquire()} 之前为 -1）。 */
    public int workerId() {
        return workerId;
    }

    /** 机房标识（由部署侧显式配置，不自动分配）。 */
    public long datacenterId() {
        return datacenterId;
    }

    /** 本实例的 JVM 标识（租约 value，用于续约/释放时确认「是自己的租约」）。 */
    public String instanceId() {
        return instanceId;
    }

    /**
     * 租约是否仍被本进程持有。
     *
     * <p>每次 {@code nextId()} 都会读它，因此这里<b>只做一个 volatile/原子读</b>，
     * 不查 Redis、不加锁——续约状态由后台线程维护。</p>
     */
    @Override
    public boolean isHeld() {
        return held.get();
    }

    // ==================== 内部 ====================

    private void validateIntervals() {
        if (ttlSeconds <= 0) {
            throw new IllegalStateException(
                    "turbofeed.snowflake.lease-ttl-seconds 必须为正数，当前: " + ttlSeconds);
        }
        if (renewIntervalSeconds <= 0) {
            throw new IllegalStateException(
                    "turbofeed.snowflake.lease-renew-interval-seconds 必须为正数，当前: " + renewIntervalSeconds);
        }
        // 续约间隔必须显著小于 TTL，否则「续约还没发生租约就过期」——中间窗口里 worker-id
        // 会被别的实例抢走，而本进程还在发号（正是要防止的撞号）。取 1/2 作为硬约束。
        if (renewIntervalSeconds * 2 > ttlSeconds) {
            throw new IllegalStateException("turbofeed.snowflake.lease-renew-interval-seconds ("
                    + renewIntervalSeconds + ") 必须不超过 lease-ttl-seconds (" + ttlSeconds + ") 的一半，"
                    + "否则续约前租约可能已过期，worker-id 会被其它实例抢走导致撞主键。");
        }
    }

    /**
     * 启动后台续约线程。
     *
     * <p><b>为什么自带线程池而不复用 {@code @Scheduled}</b>：租约续约是「丢了就资损」的关键
     * 后台任务，不该依赖全局 {@code @EnableScheduling} 是否被打开，也不该与其它定时任务的
     * 线程池抢线程。独立线程池让它的生命周期与停机释放绑定得更明确。</p>
     */
    private void startRenewing() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "tf-snowflake-lease-renew");
            t.setDaemon(true);   // 不阻止 JVM 退出
            return t;
        };
        renewScheduler = Executors.newSingleThreadScheduledExecutor(tf);
        renewScheduler.scheduleAtFixedRate(this::renew, renewIntervalSeconds, renewIntervalSeconds,
                TimeUnit.SECONDS);
    }

    /** 续约一轮：失败则拉闸（生成器随即拒绝出 ID）。 */
    void renew() {
        String key = this.leaseKey;
        if (key == null) {
            return;
        }
        try {
            Long n = redis.execute(RENEW_SCRIPT, List.of(key), instanceId, String.valueOf(ttlSeconds));
            if (n != null && n == 1L) {
                if (held.compareAndSet(false, true)) {
                    log.info("worker-id 租约恢复持有: workerId={}", workerId);
                }
                return;
            }
            // 租约已被别人持有（本进程 GC 停顿过长 / 租约过期后被抢占）：拉闸
            onLeaseLost("租约 value 已不是本实例（被其它实例抢占）");
        } catch (Exception e) {
            onLeaseLost("续约异常: " + e.getMessage());
        }
    }

    private void onLeaseLost(String reason) {
        boolean wasHeld = held.getAndSet(false);
        // 用 error 级别并重复打印：拉闸后所有依赖主键的写都会失败，这是必须被立刻看见的故障
        log.error("worker-id 租约失效，已拉闸停止生成 ID（fail-closed，继续发号会撞主键）: "
                        + "workerId={}, datacenterId={}, reason={}。所有需要生成主键的写操作（注册 / 评论 / 点赞）"
                        + "将直接失败，需恢复 Redis 后重启本实例重新抢租约。",
                workerId, datacenterId, reason);
        if (wasHeld) {
            log.error("worker-id 租约刚刚失效（此前为持有状态）——请立即检查本实例与 Redis 的连通性。");
        }
    }
}
