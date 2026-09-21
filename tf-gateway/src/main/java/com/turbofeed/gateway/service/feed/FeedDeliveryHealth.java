package com.turbofeed.gateway.service.feed;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 时间线投递的<b>可观测性</b>计数器。
 *
 * <p><b>为什么需要它</b>：投递链路长期是 fail-open + 一句 WARN。fail-open 本身没错
 * （不能让引擎抖动反向阻断审核状态机），错在<b>失败没有任何痕迹</b>：
 * 2026-09-21 的事故里，库里有 6 条 APPROVED、发现流全空，而日志里只有零散 WARN，
 * 排查要一路追到"引擎进程没起 + 引擎连错了 Redis"才定位。
 * 一个「投递失败了多少次、最后一次什么时候、因为什么」的计数器，能把这类事故从
 * "翻日志猜"变成"看一眼就知道"。</p>
 *
 * <p><b>为什么是进程内计数而不是埋点系统</b>：项目尚未接 Prometheus / Micrometer 注册表，
 * 引入一整套指标栈只为几个计数不划算。这里用 {@link AtomicLong} 就地累计，
 * 通过管理接口直接读——零新组件、零依赖，接了指标系统后把这几个计数转发过去即可。
 * 代价是多实例部署时每个实例各计各的，读接口只能反映"当前实例"；这对定位
 * "引擎是不是挂了"足够（挂了是每个实例都失败），精确统计留待指标栈。</p>
 *
 * <p><b>只统计"投递"，不统计"内容是否真的进了流"</b>：投递成功 ≠ 引擎写入成功
 * （引擎内部也是 fail-open）。真正的对账要靠 {@code FeedBackfillService} 重放。
 * 本类的定位是<b>快速发现</b>，不是精确对账。</p>
 */
@Component
public class FeedDeliveryHealth {

    private final AtomicLong appendSuccess = new AtomicLong();
    private final AtomicLong appendFailure = new AtomicLong();
    private final AtomicLong removeSuccess = new AtomicLong();
    private final AtomicLong removeFailure = new AtomicLong();
    private final AtomicLong readFailure = new AtomicLong();

    /** 最后一次失败的时间与原因（volatile 保证读线程可见；单次赋值，无复合操作）。 */
    private volatile Instant lastFailureAt;
    private volatile String lastFailureDetail;

    public void recordAppendSuccess() {
        appendSuccess.incrementAndGet();
    }

    public void recordAppendFailure(String detail) {
        appendFailure.incrementAndGet();
        mark(detail);
    }

    public void recordRemoveSuccess() {
        removeSuccess.incrementAndGet();
    }

    public void recordRemoveFailure(String detail) {
        removeFailure.incrementAndGet();
        mark(detail);
    }

    public void recordReadFailure(String detail) {
        readFailure.incrementAndGet();
        mark(detail);
    }

    private void mark(String detail) {
        lastFailureAt = Instant.now();
        lastFailureDetail = detail;
    }

    /**
     * 投递健康度快照。
     *
     * @param appendSuccess 入流投递成功次数
     * @param appendFailure 入流投递失败次数（>0 意味着有内容没进发现流）
     * @param removeSuccess 下架投递成功次数
     * @param removeFailure 下架投递失败次数（>0 意味着有已下架内容可能仍在展示）
     * @param readFailure   推荐流读取失败次数（>0 意味着发现流正在降级为空）
     * @param lastFailureAt 最后一次失败时间（null = 从未失败）
     * @param lastFailureDetail 最后一次失败原因
     */
    public record Snapshot(long appendSuccess, long appendFailure, long removeSuccess, long removeFailure,
                           long readFailure, Instant lastFailureAt, String lastFailureDetail) {

        /** 是否存在未恢复的投递问题（只看是否有失败，不判断"当前是否仍故障"）。 */
        public boolean hasFailures() {
            return appendFailure > 0 || removeFailure > 0 || readFailure > 0;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(appendSuccess.get(), appendFailure.get(), removeSuccess.get(), removeFailure.get(),
                readFailure.get(), lastFailureAt, lastFailureDetail);
    }
}
