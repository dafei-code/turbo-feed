package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.moderation.ModerationEpochBroker;
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
import com.turbofeed.gateway.service.moderation.WhitelistService;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 词库 / 白名单 epoch 广播的 Spring 接线（阶段 B2）。
 *
 * <p><b>分两层装配</b>：本配置类始终存在（负责把重建回调注册进 broker），但订阅容器、
 * 启动器由 {@link ConditionalOnProperty} 门控，兜底调度器也只在开关开启时创建——
 * 开关默认关闭时<b>不建订阅容器、不启动订阅、不建线程、不产生任何 Redis 流量</b>，
 * 行为与改造前逐字节一致。</p>
 *
 * <p><b>为什么兜底不用 {@code @Scheduled}</b>：Spring Boot 默认调度池只有 1 个线程，
 * 词库 / 白名单自己的 30s 指纹刷新也在里面。若把一次可能阻塞的 Redis 读挂进去
 * （Lettuce 默认命令超时可达数十秒），会连带拖住那两个定时器——而它们恰恰是本方案
 * 「Redis 整体不可用时」的兜底下界。因此兜底走独立守护线程，快慢与主调度池隔离。</p>
 *
 * <p><b>为什么订阅容器必须 {@code isAutoStartup()=false}</b>：与
 * {@link RedisCacheInvalidationConfig} 同一个历史坑——容器默认随上下文刷新饿汉式建连，
 * Redis 偶发未起会抛 {@code RedisConnectionFailureException} 直接否决整个应用启动。
 * 这里同样改成上下文就绪后由后台启动器重试 start()，容器一旦 started 就交给内建重连自愈。</p>
 *
 * <p><b>订阅方绝不再次广播</b>：回调只调 Service 的 {@code triggerReload()}，
 * 不调 broker.bump()——否则多实例之间会形成消息回环风暴。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ModerationEpochConfig {

    private static final Logger log = LoggerFactory.getLogger(ModerationEpochConfig.class);
    private static final long RETRY_DELAY_SECONDS = 5;
    private static final long FALLBACK_INTERVAL_SECONDS = 30;

    private final SensitiveWordService sensitiveWordService;
    private final WhitelistService whitelistService;
    private final ModerationEpochBroker broker;

    /** 仅开关开启时创建；关闭时保持 null，连线程都不建。 */
    private volatile ScheduledExecutorService fallbackScheduler;

    public ModerationEpochConfig(SensitiveWordService sensitiveWordService,
                                 WhitelistService whitelistService,
                                 ModerationEpochBroker broker) {
        this.sensitiveWordService = sensitiveWordService;
        this.whitelistService = whitelistService;
        this.broker = broker;
    }

    /** 把「收到失效通知 → 让哪个 Service 后台重建」接线；broker 不反向依赖 Service。 */
    @PostConstruct
    public void start() {
        broker.registerTrigger(ModerationEpochBroker.Kind.DICT, sensitiveWordService::triggerReload);
        broker.registerTrigger(ModerationEpochBroker.Kind.WHITELIST, whitelistService::triggerReload);
        if (!broker.isEnabled()) {
            return;
        }
        fallbackScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "moderation-epoch-fallback");
            t.setDaemon(true);
            return t;
        });
        fallbackScheduler.scheduleWithFixedDelay(this::epochFallbackSafely,
                FALLBACK_INTERVAL_SECONDS, FALLBACK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService scheduler = fallbackScheduler;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 30s 兜底：补 pub/sub 丢消息、实例启动错过消息、Redis 短暂不可达。
     * 每个 kind 独立 try，一种失败不影响另一种；异常一律吞掉并记日志。
     */
    private void epochFallbackSafely() {
        for (ModerationEpochBroker.Kind kind : ModerationEpochBroker.Kind.values()) {
            try {
                broker.ensureFresh(kind);
            } catch (Exception e) {
                log.warn("词库 epoch 兜底异常（退化为 30s 指纹刷新）: kind={}, {}", kind, e.toString());
            }
        }
    }

    @Bean
    @ConditionalOnProperty(name = FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED, havingValue = "true")
    public RedisMessageListenerContainer moderationEpochContainer(
            RedisConnectionFactory connectionFactory, ModerationEpochBroker broker) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) ->
                broker.onRemoteMessage(new String(message.getBody(), StandardCharsets.UTF_8));
        container.addMessageListener(listener, new ChannelTopic(ModerationEpochBroker.CHANNEL));
        return container;
    }

    @Bean
    @ConditionalOnProperty(name = FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED, havingValue = "true")
    public SmartLifecycle moderationEpochStarter(
            @Qualifier("moderationEpochContainer") RedisMessageListenerContainer container) {
        return new SmartLifecycle() {
            private final AtomicBoolean running = new AtomicBoolean(false);
            private final ScheduledExecutorService retryScheduler =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "moderation-epoch-subscribe-retry");
                        t.setDaemon(true);
                        return t;
                    });

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public void start() {
                if (running.compareAndSet(false, true)) {
                    tryStart();
                }
            }

            private void tryStart() {
                if (!running.get()) {
                    return;
                }
                try {
                    container.start();
                    log.info("词库/白名单 epoch 失效订阅已触发（Redis 不可达时由容器内建重连自愈）: {}",
                            ModerationEpochBroker.CHANNEL);
                } catch (Exception e) {
                    log.warn("Redis 暂不可达，词库失效订阅 {}s 后重试（退化为 30s 指纹刷新）: {}",
                            RETRY_DELAY_SECONDS, e.getMessage());
                    retryScheduler.schedule(this::tryStart, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                }
            }

            @Override
            public void stop() {
                running.set(false);
                try {
                    container.stop();
                } catch (Exception ignored) {
                    // 容器未启动时 stop 可能抛错，忽略
                }
                retryScheduler.shutdown();
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }
        };
    }
}
