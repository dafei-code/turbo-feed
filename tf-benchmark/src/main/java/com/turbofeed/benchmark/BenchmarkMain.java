package com.turbofeed.benchmark;

import com.turbofeed.benchmark.report.MarkdownReport;
import com.turbofeed.benchmark.runner.BenchResult;
import com.turbofeed.benchmark.runner.HttpProbe;
import com.turbofeed.benchmark.runner.LoadRunner;
import com.turbofeed.benchmark.scenario.Scenario;
import com.turbofeed.benchmark.scenario.ScenarioRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 压测入口：一条命令产出可复现的性能数据。
 *
 * <pre>{@code
 * # 只读场景（默认），压推荐流
 * java -cp tf-benchmark/target/classes:<deps> com.turbofeed.benchmark.BenchmarkMain \
 *   --base-url http://127.0.0.1:8080 --scenario feed \
 *   --concurrency 32 --duration 60s --warmup 10s \
 *   --phone 13800138000 --password 123456
 *
 * # 写场景：必须先给真实内容身份
 * java ... BenchmarkMain --scenario like --post-ids <postId1>,<postId2> \
 *   --concurrency 16 --duration 30s --phone ... --password ...
 * }</pre>
 *
 * <h3>退出码（给 CI 用）</h3>
 * <ul>
 *   <li>{@code 0}：成功率 ≥ 99%，可作为性能回归门禁</li>
 *   <li>{@code 1}：压测完成但成功率低于红线（数字仍会打印/落盘）</li>
 *   <li>{@code 2}：参数错误 / 登录失败 / 预热期成功率过低（配置问题，未产出有效数据）</li>
 *   <li>{@code 3}：报告写入失败</li>
 * </ul>
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (BenchOptions.HelpRequested h) {
            printUsage(System.out);
            code = 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("压测未执行: " + e.getMessage());
            code = 2;
        } catch (IOException e) {
            System.err.println("报告写入失败: " + e);
            code = 3;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * 可测试的执行入口。
     *
     * @return 退出码，语义见类注释
     */
    static int run(String[] args, PrintStream out) throws IOException {
        BenchOptions options = BenchOptions.parse(args);
        Scenario scenario = ScenarioRegistry.create(options.scenario(), options);

        HttpProbe probe = new HttpProbe(options.baseUrl());
        if (scenario.requiresAuth()) {
            if (!options.hasCredentials()) {
                throw new IllegalArgumentException(
                        "场景 " + scenario.name() + " 需要登录，请提供 --phone 与 --password");
            }
            probe.login(options.phone(), options.password());
        }

        out.println("开始压测：scenario=" + scenario.name()
                + ", baseUrl=" + options.baseUrl()
                + ", concurrency=" + options.concurrency()
                + ", duration=" + options.duration().toSeconds() + "s"
                + ", warmup=" + options.warmup().toSeconds() + "s"
                + (options.targetRps() > 0 ? ", targetRps=" + options.targetRps() : "")
                + (scenario.writesData() ? ", ⚠️ 写场景" : ""));

        LocalDateTime startedAt = LocalDateTime.now();
        BenchResult result = new LoadRunner(probe).run(scenario, options);

        String markdown = MarkdownReport.render(result, options, startedAt);
        out.println(markdown);

        if (options.output() != null) {
            Path file = MarkdownReport.write(markdown, options.output(), scenario.name(), startedAt);
            out.println("报告已写入: " + file.toAbsolutePath());
        }
        out.println(String.format(Locale.ROOT, "完成：%.1f QPS，成功率 %.2f%%，p99 %.1fms",
                result.qps(), result.successRate() * 100, result.p99Micros() / 1000.0));
        return result.successRate() >= MarkdownReport.SUCCESS_RATE_FLOOR ? 0 : 1;
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                tf-benchmark —— turbo-feed 压测器（五场景）
                
                用法:
                  java -cp <classes+deps> com.turbofeed.benchmark.BenchmarkMain [选项]
                
                必填:
                  --base-url <url>      被测服务地址，默认 http://127.0.0.1:8080
                  --scenario <name>     场景，可选: feed | playable | comment-list | like | comment-publish
                
                常用:
                  --concurrency <n>     并发线程数，默认 16
                  --duration <30s|5m>   正式压测时长，默认 30s
                  --warmup <5s>         预热时长（样本丢弃），默认 5s
                  --target-rps <n>      目标总 QPS（0=不限，跑满并发），默认 0
                  --post-ids <a,b,c>    内容身份池；除 feed 外的场景必填
                  --page-size <n>       分页大小（feed），默认 10
                  --phone / --password  登录凭据；需要鉴权的场景必填
                  --output <dir|->      报告输出目录，默认 target/benchmark；'-' 表示只打印
                  --help                显示本说明
                
                退出码: 0=达标 1=成功率低于99% 2=参数/登录失败 3=报告写入失败
                """);
    }
}
