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
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 唯一构造器：实例标识必须显式传入。
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
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId 越界");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER) {
            throw new IllegalArgumentException("datacenterId 越界");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
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
