package com.turbofeed.benchmark.runner;

import com.turbofeed.benchmark.JsonFields;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP 探针：发一次请求并给出「延迟 + HTTP 状态 + 业务码」。
 *
 * <p><b>为什么必须读业务码</b>：本项目鉴权失败返回的是 <b>HTTP 200</b> + {@code code=40101}。
 * 只看状态码的压测器会把这批请求全部记成成功，测出一个漂亮的假 QPS 与 100% 成功率——
 * 这是压测最容易骗自己的地方。因此成功判定固定为
 * {@code 2xx && businessCode == 0}。</p>
 *
 * <p><b>为什么每次调用不抛异常</b>：压测里超时/连接拒绝是<b>数据</b>不是错误，必须计入
 * 统计（失败率、错误类型分布），抛异常会让采样失真。异常被折叠成
 * {@link Outcome#error()}。</p>
 */
public final class HttpProbe {

    /** 单请求超时：压测链路必须比业务 SLA 更宽容，否则「超时」会掩盖真实延迟分布。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 一次请求的观测结果。
     *
     * @param body 仅当调用方要求捕获时非 {@code null}（热路径不捕获，避免每请求多一次字符串驻留）
     */
    public record Outcome(long latencyNanos, int httpStatus, int businessCode, String error, String body) {

        /** 成功：HTTP 2xx 且业务码为 0。 */
        public boolean success() {
            return error == null && httpStatus / 100 == 2 && businessCode == 0;
        }

        /** 稳定的错误分类键（状态码或异常名），用于报告里的错误分布。 */
        public String errorKey() {
            if (error != null) {
                return "EX:" + error;
            }
            if (httpStatus / 100 != 2) {
                return "HTTP:" + httpStatus;
            }
            return "CODE:" + businessCode;
        }
    }

    private final HttpClient client;
    private final String baseUrl;
    private volatile String bearer;

    public HttpProbe(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 固定 HTTP/1.1：多数网关对 h2c 只做 upgrade 降级，压测链路用 1.1 更接近
                // 真实客户端行为，也避免 upgrade 请求本身干扰首包延迟。
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 是否已登录（持有 Bearer）。 */
    public boolean loggedIn() {
        return bearer != null;
    }

    /**
     * 用手机号 + 密码登录并保存访问令牌。
     *
     * @throws IllegalStateException 登录失败（含服务不可达 / 业务码非 0 / 无 accessToken）
     */
    public void login(String phone, String password) {
        String path = "/api/v1/auth/login?phone=" + encode(phone) + "&password=" + encode(password);
        Outcome outcome = call(RequestSpec.post(path, null, false), true);
        if (!outcome.success()) {
            throw new IllegalStateException("登录失败：httpStatus=" + outcome.httpStatus()
                    + ", businessCode=" + outcome.businessCode()
                    + (outcome.error() == null ? "" : ", error=" + outcome.error())
                    + ", body=" + abbreviate(outcome.body()));
        }
        String token = JsonFields.stringField(outcome.body(), "accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("登录响应缺少 accessToken，body=" + abbreviate(outcome.body()));
        }
        this.bearer = token;
    }

    /** 执行一次请求（不捕获响应体）。 */
    public Outcome call(RequestSpec spec) {
        return call(spec, false);
    }

    /**
     * 执行一次请求。
     *
     * <p>延迟口径为 <b>客户端侧总耗时</b>（含连接复用、请求写出、等待首字节到读完响应体），
     * 这是用户真实感知的上界，不扣减网络基准。</p>
     *
     * @param captureBody 是否把响应体带回（仅登录等需要读字段的路径开启）
     */
    public Outcome call(RequestSpec spec, boolean captureBody) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            if (spec.auth()) {
                String token = bearer;
                if (token == null) {
                    return new Outcome(System.nanoTime() - start, -1, -1, "NOT_LOGGED_IN", null);
                }
                builder.header("Authorization", "Bearer " + token);
            }
            if ("POST".equalsIgnoreCase(spec.method())) {
                String body = spec.body() == null ? "" : spec.body();
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.nanoTime() - start;
            String body = captureBody ? response.body() : null;
            int status = response.statusCode();
            // 缺 code 字段（如网关错误页）视为失败：默认值取 -1，绝不当成成功。
            int businessCode = JsonFields.intField(response.body(), "code", -1);
            return new Outcome(elapsed, status, businessCode, null, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(System.nanoTime() - start, -1, -1, "INTERRUPTED", null);
        } catch (Exception e) {
            // 连接拒绝 / 超时 / DNS 失败等：计为失败样本，保留异常类型用于分布统计。
            return new Outcome(System.nanoTime() - start, -1, -1, e.getClass().getSimpleName(), null);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
