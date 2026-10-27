package com.turbofeed.shared.trace;

import java.util.UUID;

/**
 * 链路追踪标识的跨服务契约（M4 可观测性）。
 *
 * <p><b>只放纯 JDK 逻辑</b>：traceId 的生成、格式校验、header 名与 MDC 键都在这里，
 * 但<b>不碰 MDC、不碰 Servlet</b>——{@code org.slf4j} 与 {@code jakarta.servlet} 都是
 * tf-shared 明令禁止的第三方依赖（见 {@code ArchitectureTest}）。各服务的过滤器只负责
 * 「把本类算出的 id 放进 MDC / 写进响应头」，那是二十行管道代码，重复两份也比
 * 给所有服务塞进 servlet 依赖便宜。</p>
 *
 * <h3>为什么 traceId 要在网关入口统一采信</h3>
 * <p>没有它时，一次「用户说刷不出内容」的排查要在网关、引擎、计数三份日志里按时间戳
 * 手工对齐——而高 QPS 下同一毫秒有几十条日志，实际根本对不上。有了它，一条
 * {@code grep} 就能拉出整条链路。</p>
 *
 * <h3>为什么上游传啥不能直接用</h3>
 * <p>入站 header 是不可信输入，直接塞进 MDC 再打进日志会踩两个坑：</p>
 * <ul>
 *   <li><b>日志注入</b>：值里带 {@code \n} 可以伪造出整条假日志行（伪造审计记录、
 *       污染 grep 结果）；</li>
 *   <li><b>无界长度</b>：几十 KB 的 header 会把每条日志撑大，磁盘与采集链路先垮。</li>
 * </ul>
 * <p>故 {@link #sanitize(String)} 只放行长 ≤64 的 {@code [A-Za-z0-9._-]}，
 * 不合规一律当作「没有传」，由本服务重新生成——宁可丢上游的关联性，也不让不可信内容进日志。</p>
 *
 * <h3>已知边界</h3>
 * <p>本类只解决「同一个 HTTP 请求内的日志能串起来」。<b>MQ 消费侧目前拿不到生产者的 traceId</b>
 * （{@code FeedTimelineEvent} 契约里没有这个字段，加字段等于升 schemaVersion），
 * 消费链路会用消费时刻新生成的 id。这是如实登记的缺口，不是遗漏。</p>
 */
public final class TraceIds {

    /** 跨服务传递 traceId 的 HTTP 头。 */
    public static final String HEADER = "X-Trace-Id";

    /** 日志 MDC 中的键名；日志格式里的 {@code %X{traceId}} 必须与此一致。 */
    public static final String MDC_KEY = "traceId";

    /** traceId 最大长度：够放 UUID(32) / W3C traceparent 的 32 位十六进制，又能挡住超长 header。 */
    private static final int MAX_LENGTH = 64;

    private TraceIds() {
    }

    /**
     * 生成 32 位十六进制 traceId（UUID 去掉分隔符）。
     *
     * <p>不用 {@code Thread.currentThread().getId()} 或时间戳：前者在虚拟线程/线程池下会重复，
     * 后者在同一毫秒内不唯一——traceId 一旦重复，两笔请求的日志就混成一条，比没有更糟。</p>
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 校验上游传入的 traceId；不合规返回 {@code null}（调用方应重新生成）。
     *
     * <p>{@code null} 表示「这个值不能用」，而不是「没有 traceId」——两种情况在调用方
     * 的处理是同一个（生成新的），故不区分异常类型，避免调用方写出
     * 「catch 了却忘了返回」的分支。</p>
     *
     * @param incoming 原始 header 值，可为 {@code null}
     * @return 合规的 traceId；空白、超长、含非法字符一律 {@code null}
     */
    public static String sanitize(String incoming) {
        if (incoming == null) {
            return null;
        }
        String trimmed = incoming.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!isAllowed(trimmed.charAt(i))) {
                return null;
            }
        }
        return trimmed;
    }

    /** 白名单字符：字母数字与 {@code . _ -}（覆盖 UUID、W3C traceparent 的常用字符集）。 */
    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }
}
