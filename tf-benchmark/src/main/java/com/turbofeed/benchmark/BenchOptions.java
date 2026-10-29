package com.turbofeed.benchmark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压测参数（命令行解析结果）。
 *
 * <p>只做「解析 + 校验」，不碰 IO，便于单测。所有默认值都指向本地开发环境，
 * 但 <b>写场景必须显式给 {@code --post-ids}</b>——默认不给任何内容身份，避免误压到
 * 空数据把「0 条也要 200ms」当成性能结论。</p>
 *
 * @param baseUrl     被测服务根地址（如 {@code http://127.0.0.1:8080}）
 * @param scenario    场景名，见 {@link com.turbofeed.benchmark.scenario.ScenarioRegistry}
 * @param concurrency 并发线程数（闭合环路：每个线程串行发请求）
 * @param duration    正式压测时长
 * @param warmup      预热时长（结果不计入统计，用于让 JIT / 连接池 / 缓存进入稳态）
 * @param targetRps   目标总 QPS；{@code <=0} 表示不限速（跑满并发能力）
 * @param phone       登录手机号（只读场景可匿名，写场景必须登录）
 * @param password    登录密码
 * @param postIds     内容身份池（like / comment 场景随机取）
 * @param pageSize    分页大小（feed / comment-list 场景）
 * @param output      报告输出目录；{@code null} 表示只打印不落盘
 */
public record BenchOptions(String baseUrl,
                           String scenario,
                           int concurrency,
                           Duration duration,
                           Duration warmup,
                           int targetRps,
                           String phone,
                           String password,
                           List<String> postIds,
                           int pageSize,
                           Path output) {

    /** 解析命令行；非法参数直接 {@link IllegalArgumentException}，不静默取默认值。 */
    public static BenchOptions parse(String[] args) {
        String baseUrl = "http://127.0.0.1:8080";
        String scenario = "feed";
        int concurrency = 16;
        Duration duration = Duration.ofSeconds(30);
        Duration warmup = Duration.ofSeconds(5);
        int targetRps = 0;
        String phone = null;
        String password = null;
        List<String> postIds = List.of();
        int pageSize = 10;
        Path output = Path.of("target", "benchmark");

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (!raw.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数（应以 -- 开头）: " + raw);
            }
            String key;
            String value;
            int eq = raw.indexOf('=');
            if (eq > 0) {
                key = raw.substring(2, eq);
                value = raw.substring(eq + 1);
            } else {
                key = raw.substring(2);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("参数缺少取值: " + raw);
                }
                value = args[++i];
            }
            switch (key) {
                case "base-url" -> baseUrl = trimTrailingSlash(value);
                case "scenario" -> scenario = value;
                case "concurrency" -> concurrency = parseInt(key, value);
                case "duration" -> duration = parseDuration(key, value);
                case "warmup" -> warmup = parseDuration(key, value);
                case "target-rps" -> targetRps = parseInt(key, value);
                case "phone" -> phone = value;
                case "password" -> password = value;
                case "post-ids" -> postIds = splitCsv(value);
                case "page-size" -> pageSize = parseInt(key, value);
                case "output" -> output = "-".equals(value) ? null : Path.of(value);
                case "help", "h" -> throw new HelpRequested();
                default -> throw new IllegalArgumentException("未知参数: --" + key);
            }
        }

        if (concurrency <= 0) {
            throw new IllegalArgumentException("--concurrency 必须 > 0，实际=" + concurrency);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("--duration 必须 > 0");
        }
        if (warmup.isNegative()) {
            throw new IllegalArgumentException("--warmup 不能为负");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("--page-size 必须 > 0");
        }
        return new BenchOptions(baseUrl, scenario, concurrency, duration, warmup, targetRps,
                phone, password, postIds, pageSize, output);
    }

    /** 是否已配置登录凭据。 */
    public boolean hasCredentials() {
        return phone != null && !phone.isBlank() && password != null && !password.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        String v = url.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 需要整数，实际=" + value);
        }
    }

    /**
     * 时长解析：支持 {@code 30s} / {@code 5m} / {@code 1h} / 纯数字（按秒）。
     * 只支持整单位，够用且不给「1.5m 到底是多少」留歧义。
     */
    static Duration parseDuration(String key, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("参数 --" + key + " 为空");
        }
        char unit = v.charAt(v.length() - 1);
        try {
            if (unit == 's') {
                return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (unit == 'm') {
                return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (unit == 'h') {
                return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 时长格式非法（如 30s/5m/1h），实际=" + value);
        }
    }

    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** 用户请求帮助：由 {@code BenchmarkMain} 捕获后打印用法并以 0 退出。 */
    public static final class HelpRequested extends RuntimeException {
        public HelpRequested() {
            super("help");
        }
    }
}
