package com.turbofeed.gateway.client;

/**
 * tf-counter 缓冲待落库负载快照（网关侧 DTO，反序列化自 {@code GET /internal/counter/buckets}）。
 *
 * <p>与 tf-counter 的 {@code CounterBuffer.PendingStats} 字段一一对应；网关不依赖 tf-counter 模块，
 * 故各自定义一份，避免跨模块耦合。</p>
 */
public record CounterLoad(long pendingPostCount, long totalIncrements,
                          long like, long comment, long share, long commentLike) {
}
