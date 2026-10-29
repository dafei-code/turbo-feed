package com.turbofeed.benchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.turbofeed.benchmark.runner.BenchResult;
import com.turbofeed.benchmark.runner.HttpProbe;
import com.turbofeed.benchmark.runner.LoadRunner;
import com.turbofeed.benchmark.runner.RequestSpec;
import com.turbofeed.benchmark.scenario.Scenario;
import com.turbofeed.benchmark.scenario.ScenarioContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 压测器端到端单测：起一个 JDK 内置 {@link HttpServer} 当被测服务，验证
 * <b>统计口径</b>而不是 HTTP 本身。
 *
 * <p>用真实 HTTP 而非 mock {@link HttpProbe}：压测器的价值全在「并发 + 计时 + 判定」这三件事
 * 的协同上，把探针 mock 掉等于把要测的东西测没了。本地 loopback 起服务足够快，也能进 CI。</p>
 */
class LoadRunnerTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/login",
                ex -> respond(ex, 200, "{\"code\":0,\"data\":{\"accessToken\":\"tok\"}}"));
        server.createContext("/ok", ex -> respond(ex, 200, "{\"code\":0,\"data\":{}}"));
        // 本项目鉴权失败的形状：HTTP 200 + 业务码非 0
        server.createContext("/bizfail", ex -> respond(ex, 200, "{\"code\":40101,\"message\":\"未登录\"}"));
        server.createContext("/boom", ex -> respond(ex, 500, "{\"code\":50000}"));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void countsSuccessAndComputesPercentiles() {
        BenchResult r = run("/ok", false, Duration.ofMillis(600), Duration.ZERO);

        assertThat(r.total()).isPositive();
        assertThat(r.success()).isEqualTo(r.total());
        assertThat(r.successRate()).isEqualTo(1.0);
        assertThat(r.failed()).isZero();
        assertThat(r.qps()).isPositive();
        assertThat(r.errors()).isEmpty();
        // 分位必须单调，否则说明排序/取值有 bug
        assertThat(r.p50Micros()).isLessThanOrEqualTo(r.p95Micros());
        assertThat(r.p95Micros()).isLessThanOrEqualTo(r.p99Micros());
        assertThat(r.p99Micros()).isLessThanOrEqualTo(r.maxMicros());
    }

    /**
     * 核心回归：<b>HTTP 200 + 业务码 40101 必须算失败</b>。
     * 只看状态码的实现会在这里得出 100% 成功率的假结论。
     */
    @Test
    void businessErrorWithHttp200CountedAsFailure() {
        BenchResult r = run("/bizfail", false, Duration.ofMillis(400), Duration.ZERO);

        assertThat(r.total()).isPositive();
        assertThat(r.success()).isZero();
        assertThat(r.successRate()).isZero();
        assertThat(r.errors()).containsKey("CODE:40101");
    }

    @Test
    void httpErrorCountedAsFailure() {
        BenchResult r = run("/boom", false, Duration.ofMillis(400), Duration.ZERO);

        assertThat(r.success()).isZero();
        assertThat(r.errors()).containsKey("HTTP:500");
    }

    /** 需要鉴权却未登录：请求不该静默变成匿名请求，而应记为明确失败。 */
    @Test
    void authRequiredWithoutTokenFails() {
        BenchResult r = run("/ok", true, Duration.ofMillis(300), Duration.ZERO);

        assertThat(r.success()).isZero();
        assertThat(r.errors()).containsKey("EX:NOT_LOGGED_IN");
    }

    /** 预热期就大面积失败（配置错了）必须中止，而不是产出一份全失败的「报告」。 */
    @Test
    void lowWarmupSuccessRateAborts() {
        BenchOptions options = new BenchOptions(baseUrl, "t", 2,
                Duration.ofMillis(300), Duration.ofMillis(300), 0, null, null, List.of(), 10, null);

        assertThatThrownBy(() -> new LoadRunner(new HttpProbe(baseUrl)).run(scenario("/bizfail", false), options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预热期成功率");
    }

    private BenchResult run(String path, boolean auth, Duration duration, Duration warmup) {
        BenchOptions options = new BenchOptions(baseUrl, "t", 4, duration, warmup,
                0, null, null, List.of(), 10, null);
        return new LoadRunner(new HttpProbe(baseUrl)).run(scenario(path, auth), options);
    }

    private static Scenario scenario(String path, boolean auth) {
        return new Scenario() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public boolean requiresAuth() {
                return auth;
            }

            @Override
            public boolean writesData() {
                return false;
            }

            @Override
            public RequestSpec next(ScenarioContext ctx) {
                return RequestSpec.get(path, auth);
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
