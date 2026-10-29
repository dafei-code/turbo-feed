package com.turbofeed.benchmark.runner;

import java.util.List;
import java.util.Map;

/**
 * 一次压测的结果（报告的唯一数据来源）。
 *
 * <p>延迟单位对外统一用<b>微秒</b>：压测讨论里毫秒太粗（p99 差 0.3ms 往往是两倍关系），
 * 纳秒又不直观。报告里再按需换算。</p>
 *
 * @param scenario       场景名
 * @param writesData     是否产生真实写入（报告据此标注）
 * @param concurrency    并发线程数
 * @param durationMillis 实际压测时长（不含预热）
 * @param total          正式期总请求数
 * @param success        成功请求数（HTTP 2xx 且业务码 0）
 * @param failed         失败请求数
 * @param qps            实际吞吐（total / 实际时长）
 * @param p50Micros      延迟分位（微秒）
 * @param p90Micros      延迟分位（微秒）
 * @param p95Micros      延迟分位（微秒）
 * @param p99Micros      延迟分位（微秒）
 * @param maxMicros      最大延迟（微秒）
 * @param meanMicros     平均延迟（微秒）
 * @param errors         错误分布：分类键 → 次数（{@code HTTP:500} / {@code CODE:40101} / {@code EX:HttpTimeoutException}）
 */
public record BenchResult(String scenario,
                          boolean writesData,
                          int concurrency,
                          long durationMillis,
                          long total,
                          long success,
                          long failed,
                          double qps,
                          long p50Micros,
                          long p90Micros,
                          long p95Micros,
                          long p99Micros,
                          long maxMicros,
                          long meanMicros,
                          Map<String, Long> errors) {

    /** 成功率（0~1）。total 为 0 时返回 0，不抛除零。 */
    public double successRate() {
        return total == 0 ? 0.0 : (double) success / total;
    }

    /** 错误分布按次数降序（报告展示用）。 */
    public List<Map.Entry<String, Long>> topErrors() {
        return errors.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();
    }
}
