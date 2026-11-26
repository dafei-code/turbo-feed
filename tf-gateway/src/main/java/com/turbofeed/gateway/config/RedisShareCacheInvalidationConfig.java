package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.share.ShareMappingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 热点短链 L1 缓存的跨实例失效广播（订阅侧，方案 §4.6 / P0-3b）。
 *
 * <p><b>职责</b>：每个网关实例订阅 {@link ShareMappingCache#INVALIDATE_CHANNEL}，收到消息后只调
 * {@code invalidateLocal} 失效本实例条目——<b>绝不再次广播</b>，否则多实例形成回环风暴。
 * 发布侧在 {@link ShareMappingCache#invalidate()}（运营作废 / 下架链路）里。</p>
 *
 * <p><b>与账号状态缓存配置隔离</b>：独立容器，本链路的抖动不牵连限流/账号状态缓存，反之亦然。</p>
 *
 * <p><b>启动韧性</b>：容器 {@code isAutoStartup()=false}，由 {@code shareCacheInvalidationStarter}
 * 在上下文刷新后启动；Redis 不可达时按间隔重试 start()，网关照常启动，期间退化为 TTL 兜底。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turbofeed.share.cache-broadcast-enabled", havingValue = "true", matchIfMissing = true)
public class RedisShareCacheInvalidationConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisShareCacheInvalidationConfig.class);
    private static final long RETRY_DELAY_SECONDS = 5;

    @Bean
    public RedisMessageListenerContainer shareCacheInvalidationContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory,
            ShareMappingCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            cache.onInvalidateMessage(body);
        };
        container.addMessageListener(listener, new ChannelTopic(ShareMappingCache.INVALIDATE_CHANNEL));
        return container;
    }

    @Bean
    public SmartLifecycle shareCacheInvalidationStarter(
            @Qualifier("shareCacheInvalidationContainer") RedisMessageListenerContainer container) {
        return new SmartLifecycle() {
            private final AtomicBoolean running = new AtomicBoolean(false);
            private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "share-cache-invalidation-retry");
                t.setDaemon(true);
                return t;
            });

            @Override
            public void start() {
                retry.scheduleWithFixedDelay(() -> {
                    if (container.isRunning()) {
                        return;
                    }
                    try {
                        container.start();
                        log.info("短链缓存失效订阅已建立");
                    } catch (Exception e) {
                        log.debug("短链缓存失效订阅待重试（Redis 未就绪，退化为 TTL 兜底）: {}", e.toString());
                    }
                }, 0, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                running.set(true);
            }

            @Override
            public void stop() {
                running.set(false);
                retry.shutdownNow();
                if (container.isRunning()) {
                    container.stop();
                }
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE; // 上下文刷新之后最后启动
            }
        };
    }
}
