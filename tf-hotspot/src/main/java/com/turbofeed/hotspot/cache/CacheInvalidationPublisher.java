package com.turbofeed.hotspot.cache;

/**
 * 缓存失效广播发布者契约（SDK 只定义接口，不耦合具体中间件）。
 *
 * <p><b>为什么需要这一层</b>：本地缓存（L1）多实例部署时，A 实例改了状态、B 实例缓存还旧——
 * 不一致窗口是整段 TTL。用 pub/sub 把「状态变更」广播给其它实例，让它们只失效本地条目，
 * 把窗口压到「一次网络往返」。但广播的实现（Redis / RocketMQ / 自研）因部署而异，
 * 故 SDK 只定义 {@code publish(channel, payload)} 契约，由接入方提供实现。</p>
 *
 * <p><b>失败语义</b>：实现方应吞掉发送异常。广播是<b>加速手段</b>，绝不能反向拖垮写路径——
 * 发送失败由调用方退化为「TTL 兜底」（最长整段 TTL 的不一致，功能不受影响）。</p>
 */
@FunctionalInterface
public interface CacheInvalidationPublisher {

    /**
     * 把失效消息发到指定频道。
     *
     * @param channel 订阅频道（每个缓存实例独立一个）
     * @param payload 消息体，格式约定为 {@code instanceId:key}（见 {@link LocalCacheWithBroadcast}）
     */
    void publish(String channel, String payload);
}
