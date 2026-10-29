package com.turbofeed.benchmark.report;

import com.turbofeed.benchmark.BenchOptions;
import com.turbofeed.benchmark.runner.BenchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Markdown 报告：把 {@link BenchResult} 渲染成可直接贴进 PR / 周报的表格。
 *
 * <p><b>为什么报告里要有「自动判读」</b>：压测数字最容易的用法就是「看起来还行」。
 * 这里把三条硬性判据写进报告：成功率低于 99%、p99 超过 500ms、出现鉴权类错误码——
 * 命中就显式写出来，避免一份全绿的表掩盖了「其实一半请求是 401」。</p>
 *
 * <p>纯函数 + 一次落盘，不读环境变量、不看时钟（时间戳由调用方传入），便于单测断言。</p>
 */
public final class MarkdownReport {

    /** 成功率红线：低于它说明压测结果不可用（错误路径的耗时没有参考价值）。 */
    public static final double SUCCESS_RATE_FLOOR = 0.99;

    /** p99 红线（微秒）：与项目「读请求 p99 < 500ms」的既有口径对齐。 */
    static final long P99_CEILING_MICROS = 500_000L;

    /** 鉴权类业务码前缀（401xx）：出现即说明压测跑在未登录状态，数字全废。 */
    private static final String AUTH_ERROR_PREFIX = "CODE:401";

    private MarkdownReport() {
    }

    /**
     * 渲染报告。
     *
     * @param result    压测结果
     * @param options   运行参数（用于回显压测条件，保证「可复现」）
     * @param startedAt 压测开始时间（由调用方传入，便于回放）
     */
    public static String render(BenchResult result, BenchOptions options, LocalDateTime startedAt) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 压测报告 · ").append(result.scenario()).append('\n').append('\n');

        if (result.writesData()) {
            sb.append("> ⚠️ **写场景**：本次压测对被测环境产生了真实数据（点赞/评论），")
                    .append("请确认环境可清理后再引用本报告。\n\n");
        }

        sb.append("## 压测条件\n\n");
        sb.append("| 项 | 值 |\n| --- | --- |\n");
        sb.append("| 目标服务 | `").append(options.baseUrl()).append("` |\n");
        sb.append("| 场景 | `").append(result.scenario()).append("` |\n");
        sb.append("| 并发 | ").append(result.concurrency()).append(" |\n");
        sb.append("| 正式时长 | ").append(result.durationMillis() / 1000.0).append(" s |\n");
        sb.append("| 预热时长 | ").append(options.warmup().toMillis() / 1000.0).append(" s（样本已丢弃） |\n");
        sb.append("| 目标 QPS | ").append(options.targetRps() > 0
                ? String.valueOf(options.targetRps()) : "不限（跑满并发）").append(" |\n");
        sb.append("| 内容身份池 | ").append(options.postIds().isEmpty()
                ? "—" : options.postIds().size() + " 个").append(" |\n");
        sb.append("| 开始时间 | ").append(startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" |\n");
        sb.append("| 压测机 | ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" / ")
                .append(Runtime.getRuntime().availableProcessors()).append(" 核 / JDK ")
                .append(System.getProperty("java.version")).append(" |\n\n");

        sb.append("## 吞吐与成功率\n\n");
        sb.append("| 总请求 | 成功 | 失败 | 成功率 | 实际 QPS |\n| ---: | ---: | ---: | ---: | ---: |\n");
        sb.append("| ").append(result.total())
                .append(" | ").append(result.success())
                .append(" | ").append(result.failed())
                .append(" | ").append(String.format(Locale.ROOT, "%.2f%%", result.successRate() * 100))
                .append(" | ").append(String.format(Locale.ROOT, "%.1f", result.qps()))
                .append(" |\n\n");

        sb.append("## 延迟分布（客户端侧，含网络与序列化）\n\n");
        sb.append("| 平均 | p50 | p90 | p95 | p99 | max |\n");
        sb.append("| ---: | ---: | ---: | ---: | ---: | ---: |\n");
        sb.append("| ").append(ms(result.meanMicros()))
                .append(" | ").append(ms(result.p50Micros()))
                .append(" | ").append(ms(result.p90Micros()))
                .append(" | ").append(ms(result.p95Micros()))
                .append(" | ").append(ms(result.p99Micros()))
                .append(" | ").append(ms(result.maxMicros()))
                .append(" |\n\n");
        sb.append("> 单位毫秒；括号内为原始微秒值。分位口径为最近秩（nearest-rank），不插值。\n\n");

        List<Map.Entry<String, Long>> errors = result.topErrors();
        if (!errors.isEmpty()) {
            sb.append("## 错误分布\n\n");
            sb.append("| 分类 | 次数 |\n| --- | ---: |\n");
            for (Map.Entry<String, Long> e : errors) {
                sb.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 自动判读\n\n");
        for (String note : verdict(result, errors)) {
            sb.append("- ").append(note).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 三条硬性判据 + 一条正向结论。
     *
     * @return 每条一行 Markdown 文本（不含列表符号）
     */
    static List<String> verdict(BenchResult result, List<Map.Entry<String, Long>> errors) {
        List<String> notes = new java.util.ArrayList<>(4);
        if (result.total() == 0) {
            notes.add("❌ **没有任何请求完成**：并发线程在截止时间内一条都没发出去，"
                    + "通常是服务不可达或首请求就阻塞。本报告无参考价值。");
            return notes;
        }
        if (result.successRate() < SUCCESS_RATE_FLOOR) {
            notes.add(String.format(Locale.ROOT,
                    "❌ **成功率 %.2f%% 低于 %.0f%% 红线**：失败请求的耗时不计入有效吞吐，"
                            + "本报告的 QPS 与延迟需按成功请求重估。",
                    result.successRate() * 100, SUCCESS_RATE_FLOOR * 100));
        } else if (result.failed() > 0) {
            // 99% 是「报告可用」的底线，不是 SLO。对读接口来说 0.6% 的失败率已经很高，
            // 不能因为没跌破红线就给一个纯 ✅ 让人放心走开。
            notes.add(String.format(Locale.ROOT,
                    "🟡 **成功率 %.2f%% 在红线内，但仍有 %d 条失败**（%.2f%%）："
                            + "读接口对失败率通常更敏感，请按上方错误分布排查后再引用 QPS。",
                    result.successRate() * 100, result.failed(), 100 - result.successRate() * 100));
        }
        boolean authIssue = errors.stream().anyMatch(e -> e.getKey().startsWith(AUTH_ERROR_PREFIX));
        if (authIssue) {
            notes.add("❌ **出现鉴权类错误（401xx）**：服务端把请求判为未登录/无权限。"
                    + "本项目的这类失败仍是 **HTTP 200**，只看状态码的脚本会把它当成功——"
                    + "请检查 `--phone/--password` 与令牌有效期。");
        }
        if (result.p99Micros() > P99_CEILING_MICROS) {
            notes.add(String.format(Locale.ROOT,
                    "🟡 **p99 %.1fms 超过 %.0fms 参考线**：尾部延迟偏高，建议结合服务端 GC / 慢查询排查。",
                    result.p99Micros() / 1000.0, P99_CEILING_MICROS / 1000.0));
        }
        if (notes.isEmpty()) {
            notes.add("✅ 成功率与尾延迟均在参考线内；结论可复现（相同并发/时长/数据池下重跑误差应 <10%）。");
        }
        return notes;
    }

    /** 微秒 → 「12.34 ms (12340 µs)」；小于 1ms 时直接给微秒，避免显示成 0.00。 */
    private static String ms(long micros) {
        if (micros < 1000) {
            return micros + " µs";
        }
        return String.format(Locale.ROOT, "%.2f ms (%d µs)", micros / 1000.0, micros);
    }

    /**
     * 写报告到目录（自动创建），文件名为 {@code benchmark-<场景>-<时间戳>.md}。
     *
     * @return 实际写入的文件路径
     */
    public static Path write(String markdown, Path outputDir, String scenario, LocalDateTime startedAt)
            throws IOException {
        Files.createDirectories(outputDir);
        String name = "benchmark-" + scenario + "-"
                + startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md";
        Path file = outputDir.resolve(name);
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        return file;
    }
}
