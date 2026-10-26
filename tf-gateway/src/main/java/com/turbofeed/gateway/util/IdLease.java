package com.turbofeed.gateway.util;

/**
 * 雪花实例标识（worker-id）租约的<b>健康闸门</b>（M2 / 4.3）。
 *
 * <p><b>它为什么存在</b>：worker-id 靠 Redis 租约自动分配后，进程必须在<b>整个生命周期内持续
 * 持有</b>它。租约过期（续约失败）而进程还在发号，等于<b>静默地与另一个实例共用同一个
 * worker-id</b>——两者在同一毫秒生成完全相同的 ID，user / comment 主键直接冲突。
 * 这类故障不会报错在发号那一刻，而是表现为「偶发注册失败」，排查成本极高。</p>
 *
 * <p>因此闸门是 <b>fail-closed</b> 的：租约没了就拒绝发号，绝不在不确定状态下继续生成主键。</p>
 *
 * <p><b>为什么定义成接口而不是让生成器直接依赖租约实现类</b>：{@link SnowflakeIdGenerator} 在
 * {@code util} 包，是纯 JDK 的工具类；租约实现要 Spring + Redis。用接口反转依赖后，
 * 生成器只在构造时被<b>可选地</b>注入一个闸门，既不认识 Spring 也不认识 Redis，
 * 不启用租约时（显式配置模式）传 {@code null} 即可，零开销、零侵入。</p>
 */
@FunctionalInterface
public interface IdLease {

    /**
     * 租约当前是否仍被本进程持有。
     *
     * <p>由租约管理方的续约线程维护：续约成功置 {@code true}，续约失败置 {@code false}。
     * 本方法必须<b>无副作用、不阻塞、不走网络</b>——它在每一次 {@code nextId()} 的同步块里被调用，
     * 是 ID 生成的热路径。</p>
     */
    boolean isHeld();
}
