package com.turbofeed.shared.trace;

import java.lang.management.ManagementFactory;

/**
 * 进程实例标识（M4 可观测性，纯 JDK）。
 *
 * <p><b>为什么需要它</b>：生产是多副本部署，同一秒的 N 个实例都会打出
 * 「发件箱补偿一轮: due=3」「flush 整批重放被去重拦下」这类聚合日志。只有 traceId 时，
 * 这些行在集中日志里既无法区分来源实例，也无法区分是不是同一轮调度——排查时只能靠时间戳猜。
 * 把 {@code instanceId} 与 {@code cycleId} 一起放进 MDC，是让这些行「自描述」的最低代价。</p>
 *
 * <h3>为什么放在 tf-shared 且只用 JDK</h3>
 * <p>键名契约与生成逻辑应对三个服务完全一致；而 tf-shared 的 {@code ArchitectureTest}
 * 明令禁止任何非 JDK 依赖，所以这里只用 {@code System.getenv} 与
 * {@link ManagementFactory}，不碰 MDC、不碰 Servlet、不引第三方库。</p>
 *
 * <h3>取值优先级</h3>
 * <ol>
 *   <li>{@code POD_NAME} —— K8s downward API 显式注入时最准确；</li>
 *   <li>{@code HOSTNAME} —— 容器默认即 Pod 名 / 容器短 id，K8s 与 Compose 都适用；</li>
 *   <li>{@code COMPUTERNAME} —— Windows 开发机；</li>
 *   <li>JVM 运行时名 {@code pid@host} —— 最后兜底，保证永不返回空。</li>
 * </ol>
 *
 * <p><b>为什么要规范化</b>：环境变量是外部输入，直接进 MDC 再打进日志会踩与
 * {@link TraceIds#sanitize} 相同的两个坑（换行伪造日志行、无界长度撑爆采集链路）。
 * 这里不丢弃值（丢了就等于没有实例标识），而是把非法字符替换为 {@code -} 并截断到 64 字符。</p>
 *
 * <p>进程内取值恒定，故缓存为静态常量——它不随请求变化，不必每次查环境变量。</p>
 */
public final class InstanceIds {

    private static final int MAX_LENGTH = 64;

    private static final String VALUE = resolve();

    private InstanceIds() {
    }

    /** 当前进程实例标识：非空、长度 ≤64、只含 {@code [A-Za-z0-9._-]}。 */
    public static String current() {
        return VALUE;
    }

    /** 把任意外部输入规范化为可安全写入日志与 MDC 的值（包级可见以便单测）。 */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String trimmed = raw.trim();
        int length = Math.min(trimmed.length(), MAX_LENGTH);
        StringBuilder safe = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = trimmed.charAt(i);
            safe.append(isAllowed(c) ? c : '-');
        }
        return safe.toString();
    }

    private static String resolve() {
        String raw = firstNonBlank(System.getenv("POD_NAME"),
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"));
        if (raw == null) {
            raw = ManagementFactory.getRuntimeMXBean().getName();
        }
        return normalize(raw);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }
}
