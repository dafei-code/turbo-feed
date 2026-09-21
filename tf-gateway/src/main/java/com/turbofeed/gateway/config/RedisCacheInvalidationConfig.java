package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.state.AccountStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 账号状态本地缓存的<b>跨实例失效广播</b>（Redis pub/sub 订阅侧）。
 *
 * <p><b>它解决什么</b>：{@link AccountStateCache} 是进程内缓存，多实例部署时
 * 「A 实例封禁了某账号，B 实例的缓存还认为可写」——改造前这个不一致窗口是整个 TTL（默认 15s）。
 * 本类让每个实例订阅同一频道，收到消息后只失效本地条目，把窗口压到一次 Redis 往返。</p>
 *
 * <p><b>为什么用 pub/sub 而不是「把缓存搬到 Redis」</b>：搬到 Redis 等于把「本可不发生的读」
 * 换成「每次必打的网络往返」，与本地缓存的初衷（消除 DB 读）南辕北辙。
 * pub/sub 只在<b>状态变更</b>时产生流量——而状态变更（封禁/扣分）是极低频操作。</p>
 *
 * <p><b>为什么订阅侧与发布侧分开</b>：发布在 {@link AccountStateCache#invalidate} 里
 * （写路径必经），订阅在容器里回调 {@code invalidateLocal}——
 * <b>订阅方绝不能再次广播</b>，否则多实例之间会形成消息回环风暴。</p>
 *
 * <p><b>Redis 不可用时的行为</b>：{@code RedisMessageListenerContainer} 会自行重连，
 * 期间收不到广播，一致性退化为「TTL 兜底」（最长 15s）——功能不受影响，只是回到改造前的口径。
 * 这也是为什么不能把「广播失效」当成唯一的一致性保障：TTL 才是下界。</p>
 *
 * <p><b>可关</b>：{@code turbofeed.state-cache.broadcast-enabled=false} 时本配置不装配
 * （发布侧同步关闭，见 {@code AccountStateCache#invalidate}）。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turbofeed.state-cache.broadcast-enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheInvalidationConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationConfig.class);

    /**
     * 订阅容器：进程启动时即建立订阅，收到消息回调 {@link AccountStateCache#onInvalidateMessage}。
     *
     * <p>⚠️ 刻意<b>不共用</b>项目里其它 Redis 组件的容器：本容器只承担缓存失效这一件事，
     * 它的抖动不应牵连限流等其它链路，反之亦然。</p>
     */
    @Bean
    public RedisMessageListenerContainer accountStateCacheInvalidationContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory,
            AccountStateCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            cache.onInvalidateMessage(body);
        };
        container.addMessageListener(listener, new ChannelTopic(AccountStateCache.INVALIDATE_CHANNEL));
        log.info("已订阅账号状态缓存失效频道: {}", AccountStateCache.INVALIDATE_CHANNEL);
        return container;
    }
}
