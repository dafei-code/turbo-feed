package com.turbofeed.benchmark.runner;

import com.turbofeed.benchmark.BenchOptions;
import com.turbofeed.benchmark.scenario.Scenario;
import com.turbofeed.benchmark.scenario.ScenarioContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

/**
 * 闭合环路压测器：{@code concurrency} 个线程各自「发一条 → 收到后再发下一条」，持续到截止时间。
 *
 * <h3>为什么是闭合环路</h3>
 * <p>闭合环路的语义是「N 个并发用户」，与实际客户端行为一致，也是多数性能结论的默认口径。
 * 若要做恒定到达率（open-loop，可测排队延迟），用 {@code --target-rps} 节流——但要清楚
 * 那只是<b>上限近似</b>：服务变慢时线程发不出那么多请求，实际到达率会掉下来，不会像真
 * open-loop 那样继续压进队列。真 open-loop 需要独立调度线程池，本模块刻意不做：
 * 「测过载下的排队长度」属于容量规划，不是日常回归的需求。</p>
 *
 * <h3>预热</h3>
 * <p>正式期前先空跑 {@code warmup}，样本全部丢弃。没有预热的话，前几秒样本会被 JIT 编译、
 * 连接建立、服务端缓存冷启动污染，p99 直接翻倍——那不是系统性能，是启动成本。</p>
 *
 * <h3>线程模型</h3>
 * <p>每线程持有独立的 {@link ScenarioContext}（随机源无锁）与 {@link LatencyCollector}
 * （本地数组顺序写）。统计在结束后合并，热路径上没有任何跨线程同步。</p>
 */
public final class LoadRunner {

    private final HttpProbe probe;

    public LoadRunner(HttpProbe probe) {
        this.probe = probe;
    }

    /**
     * 跑一次完整压测（含预热）。
     *
     * @throws IllegalStateException 预热期成功率过低（配置错了，继续测只会产出全失败的假报告），
     *                               或压测线程异常/被中断
     */
    public BenchResult run(Scenario scenario, BenchOptions options) {
        if (!options.warmup().isZero()) {
            Sample warmup = execute(scenario, options, options.warmup());
            if (warmup.total() > 0) {
                double rate = (double) warmup.success() / warmup.total();
                if (rate < 0.5) {
                    throw new IllegalStateException(String.format(
                            "预热期成功率仅 %.1f%%（%d/%d），疑似登录失效或端点不可用，已中止。错误分布=%s",
                            rate * 100, warmup.success(), warmup.total(), warmup.errors()));
                }
            }
        }
        Sample measured = execute(scenario, options, options.duration());
        return toResult(scenario, options, measured);
    }

    private BenchResult toResult(Scenario scenario, BenchOptions options, Sample s) {
        long[] all = s.mergeLatencies();
        Arrays.sort(all);
        long durationMillis = Math.max(1L, s.elapsedNanos() / 1_000_000L);
        double qps = s.total() * 1000.0 / durationMillis;
        return new BenchResult(
                scenario.name(),
                scenario.writesData(),
                options.concurrency(),
                durationMillis,
                s.total(),
                s.success(),
                s.total() - s.success(),
                qps,
                micros(percentile(all, 0.50)),
                micros(percentile(all, 0.90)),
                micros(percentile(all, 0.95)),
                micros(percentile(all, 0.99)),
                micros(all.length == 0 ? 0 : all[all.length - 1]),
                micros(mean(all)),
                s.errors());
    }

    /**
     * 执行一段压测，返回聚合样本。
     *
     * @param duration 本段时长（预热段或正式段）
     */
    private Sample execute(Scenario scenario, BenchOptions options, Duration duration) {
        int concurrency = options.concurrency();
        long deadlineNanos = System.nanoTime() + duration.toNanos();
        // 每线程节流间隔：总目标 / 线程数。仅在 --target-rps > 0 时生效。
        long throttleNanos = options.targetRps() > 0
                ? (long) (1_000_000_000.0 * concurrency / options.targetRps())
                : 0L;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "tf-bench");
            t.setDaemon(true);
            return t;
        });
        long startNanos = System.nanoTime();
        try {
            List<Future<ThreadSample>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> loop(scenario, options, deadlineNanos, throttleNanos)));
            }
            List<ThreadSample> samples = new ArrayList<>(concurrency);
            for (Future<ThreadSample> f : futures) {
                samples.add(f.get());
            }
            long elapsed = System.nanoTime() - startNanos;
            return Sample.of(samples, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("压测被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("压测线程异常退出: " + cause, cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 单个压测线程的主循环。 */
    private ThreadSample loop(Scenario scenario, BenchOptions options, long deadlineNanos, long throttleNanos) {
        ScenarioContext ctx = new ScenarioContext(options.postIds(), options.pageSize());
        LatencyCollector collector = new LatencyCollector();
        Map<String, Long> errors = new HashMap<>();
        long nextAllowed = System.nanoTime();
        while (System.nanoTime() < deadlineNanos) {
            HttpProbe.Outcome outcome = probe.call(scenario.next(ctx));
            collector.record(outcome.latencyNanos());
            if (outcome.success()) {
                collector.success();
            } else {
                errors.merge(outcome.errorKey(), 1L, Long::sum);
            }
            if (throttleNanos > 0) {
                nextAllowed += throttleNanos;
                long wait = nextAllowed - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -throttleNanos * 10) {
                    // 服务端已慢到远跟不上目标速率：把节拍基线拉回当前时刻，避免「欠账」
                    // 累积成一波突发把系统打穿——那不是我们要测的到达率。
                    nextAllowed = System.nanoTime();
                }
            }
        }
        return new ThreadSample(collector.toArray(), collector.successCount(), Map.copyOf(errors));
    }

    /** 单线程产出的样本。 */
    private record ThreadSample(long[] latencies, long success, Map<String, Long> errors) {
    }

    /** 多线程样本聚合。 */
    private static final class Sample {
        private final long total;
        private final long success;
        private final long elapsedNanos;
        private final List<ThreadSample> samples;

        private Sample(long total, long success, long elapsedNanos, List<ThreadSample> samples) {
            this.total = total;
            this.success = success;
            this.elapsedNanos = elapsedNanos;
            this.samples = samples;
        }

        static Sample of(List<ThreadSample> samples, long elapsedNanos) {
            long total = 0;
            long success = 0;
            for (ThreadSample s : samples) {
                total += s.latencies().length;
                success += s.success();
            }
            return new Sample(total, success, elapsedNanos, samples);
        }

        long total() {
            return total;
        }

        long success() {
            return success;
        }

        long elapsedNanos() {
            return elapsedNanos;
        }

        long[] mergeLatencies() {
            int size = 0;
            for (ThreadSample s : samples) {
                size += s.latencies().length;
            }
            long[] all = new long[size];
            int at = 0;
            for (ThreadSample s : samples) {
                System.arraycopy(s.latencies(), 0, all, at, s.latencies().length);
                at += s.latencies().length;
            }
            return all;
        }

        Map<String, Long> errors() {
            Map<String, Long> merged = new HashMap<>();
            for (ThreadSample s : samples) {
                s.errors().forEach((k, v) -> merged.merge(k, v, Long::sum));
            }
            return Map.copyOf(merged);
        }
    }

    /**
     * 最近秩（nearest-rank）分位：返回第 {@code ceil(p*n)} 个样本。
     *
     * <p>不用插值：压测报告的读者关心「p99 那条请求有多慢」，而不是一个不存在的插值点；
     * 最近秩是监控系统（Prometheus histogram_quantile 之外）最常用也最好解释的口径。</p>
     *
     * @param sorted 已升序排序的样本
     */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(rank, sorted.length - 1))];
    }

    static long mean(long[] samples) {
        if (samples.length == 0) {
            return 0L;
        }
        long sum = 0L;
        for (long v : samples) {
            sum += v;
        }
        return sum / samples.length;
    }

    static long micros(long nanos) {
        return nanos / 1_000L;
    }
}
