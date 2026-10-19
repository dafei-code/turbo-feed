package com.turbofeed.counter;

/**
 * 计数维度（与跨服务契约 {@code CounterIncrementEvent#type} 对齐）。
 *
 * <p>序号固定为数组下标：{@code 0=LIKE, 1=COMMENT, 2=SHARE}，缓冲与落库按此顺序聚合。</p>
 */
public enum CounterType {
    LIKE,
    COMMENT,
    SHARE;

    /** 容错解析：未知类型返回 {@code null}（调用方跳过，不抛异常）。 */
    public static CounterType of(String s) {
        if (s == null) {
            return null;
        }
        try {
            return CounterType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
