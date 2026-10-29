package com.turbofeed.benchmark;

import com.turbofeed.benchmark.report.MarkdownReport;
import com.turbofeed.benchmark.runner.BenchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告渲染测试：断言「该出现的信息一定出现」，尤其是三条硬性判读——
 * 它们的作用是防止一份全绿表格掩盖失败。
 */
class MarkdownReportTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 10, 29, 22, 0, 0);

    private static BenchOptions options() {
        return new BenchOptions("http://127.0.0.1:8080", "feed", 8,
                Duration.ofSeconds(30), Duration.ofSeconds(5), 0,
                null, null, java.util.List.of(), 10, null);
    }

    private static BenchResult result(long total, long success, long p99Micros,
                                      boolean writes, Map<String, Long> errors) {
        return new BenchResult("feed", writes, 8, 30_000, total, success, total - success,
                1200.0, 12_000, 30_000, 45_000, p99Micros, 900_000, 18_000, errors);
    }

    @Test
    void rendersAllSections() {
        String md = MarkdownReport.render(result(36_000, 36_000, 60_000, false, Map.of()), options(), STARTED);

        assertThat(md).contains("# 压测报告 · feed");
        assertThat(md).contains("## 压测条件");
        assertThat(md).contains("## 吞吐与成功率");
        assertThat(md).contains("## 延迟分布");
        assertThat(md).contains("## 自动判读");
        assertThat(md).contains("100.00%");
        assertThat(md).contains("实际 QPS");
        assertThat(md).contains("| 1200.0 |").as("QPS 保留一位小数并落在表格单元格内");
        assertThat(md).contains("2026-10-29 22:00:00");
    }

    @Test
    void healthyRunGivesPositiveVerdict() {
        String md = MarkdownReport.render(result(36_000, 36_000, 60_000, false, Map.of()), options(), STARTED);
        assertThat(md).contains("✅");
        assertThat(md).doesNotContain("❌");
    }

    /** 40101 是「HTTP 200 但未登录」，必须在判读里点名，否则读者会以为数据可信。 */
    @Test
    void flagsAuthErrors() {
        Map<String, Long> errors = Map.of("CODE:40101", 500L);
        String md = MarkdownReport.render(result(1000, 500, 80_000, false, errors), options(), STARTED);

        assertThat(md).contains("鉴权类错误");
        assertThat(md).contains("`CODE:40101`");
        assertThat(md).contains("成功率 50.00% 低于 99% 红线");
    }

    @Test
    void flagsTailLatency() {
        String md = MarkdownReport.render(result(1000, 1000, 800_000, false, Map.of()), options(), STARTED);
        assertThat(md).contains("p99 800.0ms 超过 500ms 参考线");
    }

    @Test
    void flagsWriteScenario() {
        String md = MarkdownReport.render(result(100, 100, 50_000, true, Map.of()), options(), STARTED);
        assertThat(md).contains("写场景");
        assertThat(md).contains("真实数据");
    }

    @Test
    void flagsNoSamples() {
        String md = MarkdownReport.render(result(0, 0, 0, false, Map.of()), options(), STARTED);
        assertThat(md).contains("没有任何请求完成");
    }
}
