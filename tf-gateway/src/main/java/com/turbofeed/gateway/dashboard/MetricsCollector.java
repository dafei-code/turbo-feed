package com.turbofeed.gateway.dashboard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 网关流量指标采集（进程内滚动窗口，零外部依赖）。
 *
 * <p><b>为什么进程内</b>：项目尚未接 Prometheus / Micrometer 注册表。管理后台「Feed 流量趋势」
 * 只需要一个近 60 分钟的读/写 QPS 近似值，用 {@code long[]} 环形槽即可满足，无需引入整套指标栈。
 * 代价是多实例部署时各计各的（读接口只能反映当前网关实例）；这对"看一眼流量形态"足够。</p>
 *
 * <p><b>窗口结构</b>：60 个分钟槽（数组下标 = {@code epochMinute % 60}），每槽记录该分钟的
 * 读/写请求计数；切换分钟时首次写入会先清空该槽，避免跨分钟污染。前端拿到的是按时间升序的
 * 60 点序列（缺失分钟补 0）。</p>
 *
 * <p><b>写延迟 P99</b>：保留最近 {@code MAX_LATENCY_SAMPLES} 个写请求耗时样本，快照时排序取 99 分位
 * （近似，滚动窗口内）。<b>近似 DAU</b>：记录近 60 分钟见过的 userId（{@code userId → 最后见到分钟}），
 * 快照时统计仍在窗口内的去重用户数；内存上限 {@code MAX_USERS}，超出后停止新增（极少触发）。</p>
 *
 * <p>全部 fail-open / 无锁友好：计数用原生 long 数组（单写多读场景，偶发读脏可接受），
 * 用户集合用 {@link ConcurrentHashMap}。</p>
 */
@Component
public class MetricsCollector {

    private static final int WINDOW = 60;
    private static final int MAX_LATENCY_SAMPLES = 5000;
    private static final long MAX_USERS = 2_000_000L;

    /** 各分钟槽的读/写计数；slotMinute[i] 记录该槽对应哪个 epoch 分钟（-1 表示空）。 */
    private final long[] readCount = new long[WINDOW];
    private final long[] writeCount = new long[WINDOW];
    private final long[] slotMinute = new long[WINDOW];

    /** 写请求耗时样本（滚动，O(1) 入队 + 出队）。 */
    private final ConcurrentLinkedDeque<Long> writeLatencies = new ConcurrentLinkedDeque<>();

    /** 近 60 分钟见过的 userId → 最后见到分钟（近似 DAU）。 */
    private final ConcurrentHashMap<String, Long> activeUsers = new ConcurrentHashMap<>();

    private static int slot(long epochMinute) {
        return (int) (epochMinute % WINDOW);
    }

    /** 记录一次读请求（nowMs 为当前毫秒，userId 可为 null）。 */
    public void recordRead(long nowMs, String userId) {
        long m = nowMs / 60_000L;
        int i = slot(m);
        if (slotMinute[i] != m) {
            slotMinute[i] = m;
            readCount[i] = 0;
            writeCount[i] = 0;
        }
        readCount[i]++;
        touch(userId, m);
    }

    /** 记录一次写请求（nowMs 为当前毫秒，latencyMs 为本次耗时，userId 可为 null）。 */
    public void recordWrite(long nowMs, long latencyMs, String userId) {
        long m = nowMs / 60_000L;
        int i = slot(m);
        if (slotMinute[i] != m) {
            slotMinute[i] = m;
            readCount[i] = 0;
            writeCount[i] = 0;
        }
        writeCount[i]++;
        if (writeLatencies.size() >= MAX_LATENCY_SAMPLES) {
            writeLatencies.pollFirst();
        }
        writeLatencies.addLast(latencyMs);
        touch(userId, m);
    }

    private void touch(String userId, long m) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        if (activeUsers.size() < MAX_USERS) {
            activeUsers.put(userId, m);
        }
    }

    /** 生成近 60 分钟流量快照（读/写序列按时间升序，缺失分钟补 0）。 */
    public TrafficView snapshot() {
        long nowM = System.currentTimeMillis() / 60_000L;
        List<Long> read = new ArrayList<>(WINDOW);
        List<Long> write = new ArrayList<>(WINDOW);
        for (int k = 0; k < WINDOW; k++) {
            long expect = nowM - (WINDOW - 1 - k);
            int i = slot(expect);
            if (slotMinute[i] == expect) {
                read.add(readCount[i]);
                write.add(writeCount[i]);
            } else {
                read.add(0L);
                write.add(0L);
            }
        }
        return new TrafficView(read, write, computeP99(), countActive(nowM));
    }

    private double computeP99() {
        if (writeLatencies.isEmpty()) {
            return 0;
        }
        List<Long> samples = new ArrayList<>(writeLatencies);
        samples.sort(Long::compareTo);
        int idx = (int) Math.min(samples.size() - 1, Math.ceil(samples.size() * 0.99) - 1);
        if (idx < 0) {
            idx = 0;
        }
        return samples.get(idx);
    }

    private long countActive(long nowM) {
        long cutoff = nowM - WINDOW;
        // 周期性清理过期条目以约束内存（每 4096 次采样触发一次全量裁剪）
        if (!activeUsers.isEmpty() && activeUsers.size() % 4096 == 0) {
            activeUsers.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        long c = 0;
        for (long v : activeUsers.values()) {
            if (v >= cutoff) {
                c++;
            }
        }
        return c;
    }

    /** 流量快照：读/写序列（60 点，升序）、写延迟 P99(ms)、近似 DAU。 */
    public record TrafficView(List<Long> readSeries, List<Long> writeSeries,
                              double writeP99Ms, long dauApprox) {
    }
}
