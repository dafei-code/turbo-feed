package com.turbofeed.gateway.dashboard;

import com.turbofeed.gateway.client.CounterClient;
import com.turbofeed.gateway.client.CounterLoad;
import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.service.feed.FeedDeliveryHealth;
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
import com.turbofeed.gateway.service.moderation.WhitelistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台总览数据聚合（单端点 {@code GET /api/v1/admin/dashboard} 的底座）。
 *
 * <p>聚合四类真实数据：①网关进程内流量窗口（{@link MetricsCollector}）；
 * ②引擎热点榜（{@link FeedEngineClient#hotTop}）；③计数服务缓冲负载（{@link CounterClient}）；
 * ④核心组件健康度（Redis ping / MySQL SELECT 1 / MQ 实现 / 敏感词·白名单加载数 / 时间线投递健康）。</p>
 *
 * <p><b>fail-open 原则</b>：任何一项探测失败都不应拖垮整个看板——异常项以「降级状态」呈现，
 * 其余项照常返回。例如引擎不可用 → 热点榜为空；计数服务未启用/不可达 → 该面板显示「未连接」；
 * Redis/MySQL 不可达 → 对应组件标红而非整页报错。</p>
 */
@Service
public class DashboardService {

    private final FeedEngineClient feedEngineClient;
    private final CounterClient counterClient;
    private final MetricsCollector metrics;
    private final RedisConnectionFactory redisConnectionFactory;
    private final DataSource dataSource;
    private final WhitelistService whitelistService;
    private final SensitiveWordService sensitiveWordService;
    private final FeedDeliveryHealth deliveryHealth;
    private final boolean mqEnabled;

    public DashboardService(FeedEngineClient feedEngineClient,
                            CounterClient counterClient,
                            MetricsCollector metrics,
                            RedisConnectionFactory redisConnectionFactory,
                            DataSource dataSource,
                            WhitelistService whitelistService,
                            SensitiveWordService sensitiveWordService,
                            FeedDeliveryHealth deliveryHealth,
                            @Value("${turbofeed.mq.enabled:false}") boolean mqEnabled) {
        this.feedEngineClient = feedEngineClient;
        this.counterClient = counterClient;
        this.metrics = metrics;
        this.redisConnectionFactory = redisConnectionFactory;
        this.dataSource = dataSource;
        this.whitelistService = whitelistService;
        this.sensitiveWordService = sensitiveWordService;
        this.deliveryHealth = deliveryHealth;
        this.mqEnabled = mqEnabled;
    }

    /** 组装看板视图。任意子项失败仅影响该项，不抛异常。 */
    public DashboardView build() {
        // 热点榜：一次取 Top-100，面板展示前 5，hotCount 用实际数量（封顶 100）
        List<FeedEngineClient.HotItem> hot = feedEngineClient.hotTop(100).orElse(List.of());
        List<FeedEngineClient.HotItem> top5 = hot.stream().limit(5).toList();

        MetricsCollector.TrafficView traffic = metrics.snapshot();
        CounterLoad counter = counterClient.buckets().orElse(null);

        List<ComponentStatus> components = new ArrayList<>();
        components.add(redisStatus());
        components.add(mysqlStatus());
        components.add(mqStatus());
        components.add(new ComponentStatus("敏感词词库 (AC)", "UP",
                "已加载 " + sensitiveWordService.currentSize() + " 条"));
        components.add(new ComponentStatus("白名单索引", "UP",
                "已加载 " + whitelistService.currentSize() + " 条"));
        components.add(deliveryStatus());

        return new DashboardView(System.currentTimeMillis(), traffic, top5, hot.size(), counter, components);
    }

    private ComponentStatus redisStatus() {
        try {
            String pong = redisConnectionFactory.getConnection().ping();
            if (pong == null) {
                throw new IllegalStateException("ping 返回 null");
            }
            return new ComponentStatus("Redis 集群 (收件箱 / 计数桶)", "UP", "PONG");
        } catch (Exception e) {
            return new ComponentStatus("Redis 集群 (收件箱 / 计数桶)", "DOWN",
                    "不可达: " + e.getMessage());
        }
    }

    private ComponentStatus mysqlStatus() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT 1");
            return new ComponentStatus("MySQL (ShardingSphere 批量落库)", "UP", "可达");
        } catch (Exception e) {
            return new ComponentStatus("MySQL (ShardingSphere 批量落库)", "DOWN",
                    "不可达: " + e.getMessage());
        }
    }

    private ComponentStatus mqStatus() {
        if (mqEnabled) {
            return new ComponentStatus("MQ (RocketMQ 削峰)", "UP", "已启用");
        }
        return new ComponentStatus("MQ (SPI 内存实现)", "UP", "未启用 RocketMQ（内存兜底）");
    }

    private ComponentStatus deliveryStatus() {
        FeedDeliveryHealth.Snapshot s = deliveryHealth.snapshot();
        if (s.hasFailures()) {
            return new ComponentStatus("时间线投递", "WARN",
                    "append失败=" + s.appendFailure() + ", remove失败=" + s.removeFailure()
                            + ", read失败=" + s.readFailure());
        }
        return new ComponentStatus("时间线投递", "UP", "正常");
    }

    /** 单个核心组件的健康状态。 */
    public record ComponentStatus(String name, String status, String detail) {
    }

    /** 看板完整视图（管理后台 {@code /api/v1/admin/dashboard} 的返回体 data 段）。 */
    public record DashboardView(long generatedAt,
                                MetricsCollector.TrafficView traffic,
                                List<FeedEngineClient.HotItem> hot,
                                int hotCount,
                                CounterLoad counter,
                                List<ComponentStatus> components) {
    }
}
