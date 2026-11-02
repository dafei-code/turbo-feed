package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.state.AccountStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Redis 不可用时的行为（关键修复）</b>：{@code RedisMessageListenerContainer} 默认是
 * {@code SmartLifecycle(autoStartup=true)}，上下文刷新时会<b>饿汉式</b>建立订阅，一旦 Redis
 * 连不上就抛 {@code RedisConnectionFailureException} 直接否决整个应用启动——与下方 Javadoc
 * 承诺的「不阻塞启动、退化为 TTL 兜底」自相矛盾，也是本地开发 Redis 偶发未起时反复启动失败的根因。
 * 修复做法：容器 {@code isAutoStartup()=false}（不再随上下文刷新 eager 连接，其同步抛出的异常也不会
 * 再否决启动），改由 {@link #accountStateCacheInvalidationStarter} 这个 {@code SmartLifecycle} 在上下文
 * 刷新<b>之后</b>才触发 {@code start()}；{@code start()} 在 Redis 不可达时会同步抛异常，本启动器捕获后
 * 按固定间隔<b>重试 start() 直到容器进入 started 状态</b>。容器一旦 started，其<b>内建的重连机制
 * （RecoveryBackoffExecution）会自行在 Redis 就绪后补上订阅</b>——本启动器无需、也不应在订阅层反复重试。
 * Redis 就绪前网关照常启动，期间收不到广播、一致性退化为 TTL 兜底（最长 15s）。
 * 这也是为什么不能把「广播失效」当成唯一的一致性保障：TTL 才是下界。</p>
 *
 * <p><b>可关</b>：{@code turbofeed.state-cache.broadcast-enabled=false} 时本配置不装配
 * （发布侧同步关闭，见 {@code AccountStateCache#invalidate}）。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.STATE_CACHE_BROADCAST_ENABLED, havingValue = "true", matchIfMissing = true)
public class RedisCacheInvalidationConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationConfig.class);

    /** 后台补订阅的重试间隔（Redis 就绪前不阻塞启动，仅按此节奏重试）。 */
    private static final long RETRY_DELAY_SECONDS = 5;

    /**
     * 订阅容器本身：关闭自动启动，由 {@link #accountStateCacheInvalidationStarter} 在后台启动。
     *
     * <p>⚠️ 刻意<b>不共用</b>项目里其它 Redis 组件的容器：本容器只承担缓存失效这一件事，
     * 它的抖动不应牵连限流等其它链路，反之亦然。</p>
     */
    @Bean
    public RedisMessageListenerContainer accountStateCacheInvalidationContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory,
            AccountStateCache cache) {
        // 匿名子类覆盖 isAutoStartup()=false：阻止本容器随上下文刷新被生命周期处理器 eager 启动
        // （Redis 连不上会抛 RedisConnectionFailureException 直接否决整个应用启动）。
        // 订阅改由下方 accountStateCacheInvalidationStarter 在后台重试建立，Redis 就绪前不阻塞启动。
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
        container.addMessageListener(listener, new ChannelTopic(AccountStateCache.INVALIDATE_CHANNEL));
        return container;
    }

    /**
     * 后台启动器：上下文刷新<b>之后</b>（getPhase=Integer.MAX_VALUE，最后启动）才调用容器的
     * {@code start()}。{@code start()} 在 Redis 不可达时会同步抛异常，本启动器捕获并按固定间隔
     * 重试 start() 直到容器进入 started 状态；之后订阅的重建完全交给容器内建重连机制，本启动器不再干预。
     * Redis 恢复期间网关已正常对外服务，仅缓存失效退化为 TTL 兜底。
     */
    @Bean
    public SmartLifecycle accountStateCacheInvalidationStarter(
            RedisMessageListenerContainer accountStateCacheInvalidationContainer) {
        return new SmartLifecycle() {
            private final AtomicBoolean running = new AtomicBoolean(false);
            private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "acct-cache-invalidation-retry");
                t.setDaemon(true);
                return t;
            });

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE; // 最后启动，避免拖慢应用就绪
            }

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public void start() {
                if (!running.compareAndSet(false, true)) {
                    return;
                }
                tryStart();
            }

            /**
             * 反复调用容器 start() 直到它不再同步抛异常（即容器进入 started 状态）。
             * 容器一旦 started，其内建 RecoveryBackoffExecution 会在 Redis 就绪后自动补订阅，
             * 本方法即停止重试——不再在订阅层反复干预。
             */
            private void tryStart() {
                if (!running.get()) {
                    return;
                }
                try {
                    accountStateCacheInvalidationContainer.start();
                    // 到达此处说明容器已进入 started 状态（或已是 started 的 no-op），
                    // 后续订阅重建交给容器内建重连，本启动器退出重试。
                    log.info("账号状态缓存失效订阅已触发（Redis 不可达时由容器内建重连自愈，一致性降级为 TTL 兜底）: {}",
                            AccountStateCache.INVALIDATE_CHANNEL);
                } catch (Exception e) {
                    log.warn("Redis 暂不可达，缓存失效订阅 {}s 后重试（一致性降级为 TTL 兜底）: {}",
                            RETRY_DELAY_SECONDS, e.getMessage());
                    retryScheduler.schedule(this::tryStart, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                }
            }

            @Override
            public void stop() {
                running.set(false);
                try {
                    accountStateCacheInvalidationContainer.stop();
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
