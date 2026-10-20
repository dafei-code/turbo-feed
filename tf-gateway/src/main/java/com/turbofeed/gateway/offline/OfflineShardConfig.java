package com.turbofeed.gateway.offline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * 离线作业的数据源装配（<b>严格 opt-in</b>）。
 *
 * <h3>为什么默认不装配</h3>
 * <p>它连的是<b>物理库</b>，绕过了 ShardingSphere 的路由与归并。这条通道只服务于
 *「按分片全量扫描」这类离线作业，业务代码一旦误用就会读到"没有路由保护"的物理表。
 * 因此必须显式配置 {@code turbofeed.search.rebuild.offline.ds0-url} 才装配；
 * 不配置时 {@link OfflineShardJdbc} 这个 Bean 根本不存在，任何想用它的地方都拿不到实例，
 * 也就无从误用。</p>
 *
 * <h3>为什么是两个 URL</h3>
 * <p>分片分布在两个库（{@code turbo_feed_1} / {@code turbo_feed_2}）上，生产环境这两个库
 * 通常在<b>不同的 MySQL 实例</b>上，一个连接串到不了两边。{@code ds1-url} 缺省时回退用
 * {@code ds0-url}（本机两库同实例，方便本地验证）。</p>
 *
 * <h3>口令</h3>
 * <p>不落在仓库默认配置里：{@code password} 走环境变量占位符
 * {@code TURBOFEED_DB_PASSWORD}，与 ShardingSphere 配置同一套注入机制。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "turbofeed.search.rebuild.offline", name = "ds0-url")
public class OfflineShardConfig {

    @Bean
    public OfflineShardJdbc offlineShardJdbc(
            @Value("${turbofeed.search.rebuild.offline.ds0-url}") String ds0Url,
            @Value("${turbofeed.search.rebuild.offline.ds1-url:${turbofeed.search.rebuild.offline.ds0-url}}")
            String ds1Url,
            @Value("${turbofeed.search.rebuild.offline.username:root}") String username,
            @Value("${turbofeed.search.rebuild.offline.password:}") String password) {
        return new OfflineShardJdbc(build(ds0Url, username, password), build(ds1Url, username, password));
    }

    /**
     * 直连数据源（不经过任何连接池框架）。
     *
     * <p><b>为什么用 {@code DriverManagerDataSource} 而不是 HikariCP</b>：离线作业是
     *「几分钟跑一次、并发固定几个线程」的形态，用不着生产级连接池；而它每次取连接都新建，
     * 反而避免了"离线作业长期占用业务连接池名额"这类互相拖累。真到需要限流/监控时再换。</p>
     *
     * <p><b>驱动类名必须显式写</b>：不写的话 {@code DriverManager} 靠 URL 自动推断，
     * 推断失败时错误信息非常含糊（"No suitable driver"），排查成本高。</p>
     */
    private static DataSource build(String url, String username, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }
}
