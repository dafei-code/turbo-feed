package com.turbofeed.gateway.util;

/**
 * 雪花算法 ID 生成器（应用层生成 user 表分片键 id）。
 *
 * <p><b>实例标识必须逐实例区分</b>：ID 由 {@code (时间戳, datacenterId, workerId, sequence)} 拼成，
 * 多实例共用同一组 workerId/datacenterId 时，<b>同一毫秒会生成相同 ID</b>（主键冲突）。
 * 由 {@code config.SnowflakeConfig} 以单例 Bean 装配，取值来自
 * {@code turbofeed.snowflake.worker-id / datacenter-id}（各 0~31），生产用环境变量注入。</p>
 *
 * <p>与 media 表使用的业务 media_id 相互独立（media_id 由存储层生成，
 * 形如 {@code media/{userId}/{uuid}.{ext}}）。</p>
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1_700_000_000_000L; // 自定义纪元：2023-11-14
    private static final long WORKER_BITS = 5L;
    private static final long DATACENTER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS);
    private static final long MAX_DATACENTER = ~(-1L << DATACENTER_BITS);
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private final long workerId;
    private final long datacenterId;
    /** 可选的实例标识租约闸门（M2 / 4.3）；{@code null} = 未启用租约（显式配置模式）。 */
    private final IdLease lease;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 显式配置模式构造器：不使用租约闸门（worker-id 由部署侧保证唯一）。
     *
     * <p><b>刻意不提供无参构造器</b>：历史实现带一个 {@code this(1L, 1L)} 的默认构造器，
     * 任何一处 {@code new SnowflakeIdGenerator()} 都会让实例回退到 (1,1)——多实例同毫秒撞主键的
     * P0 缺陷就是这么复发的。移除后，取值只可能来自
     * {@code config.SnowflakeConfig} 的配置注入，编译期即杜绝误用。</p>
     *
     * @param workerId     机器标识（0~31）
     * @param datacenterId 机房标识（0~31）
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this(workerId, datacenterId, null);
    }

    /**
     * 租约模式构造器：额外挂一个健康闸门。
     *
     * @param workerId     机器标识（0~31），由 {@code WorkerIdLeaseManager} 抢占得到
     * @param datacenterId 机房标识（0~31），机房维度仍由部署侧显式指定（不自动分配）
     * @param lease        租约闸门；传 {@code null} 等同于 {@link #SnowflakeIdGenerator(long, long)}
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId, IdLease lease) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId 越界");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER) {
            throw new IllegalArgumentException("datacenterId 越界");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.lease = lease;
    }

    public synchronized long nextId() {
        // 租约闸门（fail-closed）：租约丢了还继续发号，就是与其它实例共用 worker-id，
        // 同毫秒必然撞主键。宁可让注册/评论直接失败并告警，也不能产出重复主键。
        if (lease != null && !lease.isHeld()) {
            throw new IllegalStateException(
                    "worker-id 租约已失效（续约失败），拒绝生成 id：继续发号会与其它实例撞主键。"
                            + "请检查本实例与 Redis 的连通性；恢复后需重启本实例重新抢租约。");
        }
        long ts = System.currentTimeMillis();
        if (ts < lastTimestamp) {
            throw new IllegalStateException("时钟回拨，拒绝生成 id");
        }
        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & ~(-1L << SEQUENCE_BITS);
            if (sequence == 0) {
                ts = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = ts;
        return ((ts - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long last) {
        long ts = System.currentTimeMillis();
        while (ts <= last) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}
