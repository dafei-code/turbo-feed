package com.turbofeed.counter.config;

import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * ShedLock 分布式定时锁装配（<b>仅集群模式</b> {@code turbofeed.counter.redis-buffer-enabled=true} 时生效）。
 *
 * <p><b>为何与 redis-buffer 强耦合</b>：ShedLock 只解决「多副本里谁执行 flush」，
 * 不解决「缓冲在哪」。若缓冲仍是各副本各自的进程内 {@code ConcurrentHashMap}，选主后只有
 * leader 能 drain，<b>非 leader 副本的缓冲永不被 flush → 计数丢失</b>。因此 ShedLock 必须与
 * <b>共享 Redis 缓冲</b>配对：所有副本写同一份 Redis 缓冲，leader 独占 drain 它，
 * 非 leader 副本的增量也已落入共享缓冲，不会丢。本配置即用 {@code @ConditionalOnProperty}
 * 把二者绑死——standalone 模式不开锁，集群模式才开锁。</p>
 *
 * <p>锁实现用 {@link JdbcLockProvider}，直接复用 tf-counter 自有 DataSource（H2 本地 /
 * MySQL 生产通用），无需额外中间件。{@code lockAtMostFor=9s} 兜住任务卡死（超过必释放，
 * 让其他副本接管）；{@code lockAtLeastFor=4s} 防止短任务在多数调度框架下被同一副本重复抢到。</p>
 */
@Configuration
@ConditionalOnProperty(name = "turbofeed.counter.redis-buffer-enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "9s", defaultLockAtLeastFor = "4s")
public class ShedLockConfig {

    @Bean
    public JdbcLockProvider lockProvider(DataSource dataSource) {
        // H2(MySQL 模式) 与 MySQL 8 的 TIMESTAMP 列语义一致，ShedLock JDBC 模板开箱即用。
        return new JdbcLockProvider(dataSource);
    }
}
