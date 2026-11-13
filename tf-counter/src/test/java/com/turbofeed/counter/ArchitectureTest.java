package com.turbofeed.counter;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import org.apache.rocketmq.spring.core.RocketMQListener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * tf-counter 的架构门禁（M1 / 3.1）。
 *
 * <p>本服务的定位是 Redis 客户端的<b>使用方</b>，不是装配方。这条边界此前是破的：它自带
 * {@code CounterRedisConfig} 与 {@code turbofeed.counter.redis.*} 私有配置空间，于是同一套 Redis
 * 存在两套连接参数——切集群时改了 Lettuce 侧、Redisson 侧没改，不报错，只表现成
 * 「计数 L2 读不到」这类静默失效。M1/3.2 已收口，本规则负责<b>防回潮</b>。</p>
 */
@AnalyzeClasses(packages = "com.turbofeed.counter", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 不得自建 Redis 客户端或连接工厂：{@code RedissonClient} 只能由 tf-redis 按统一拓扑装配后注入。
     *
     * <p><b>使用是允许的</b>：{@code RedisCounterStore} / {@code RedisCounterBuffer} 注入
     * {@code RedissonClient} 读写计数是被允许的正常用法（那正是 tf-redis 存在的意义）。
     * 被禁的是把客户端<b>造出来</b>的入口——{@code Redisson.create} / {@code Config} /
     * {@code RedisClient} / {@code LettuceConnectionFactory}。</p>
     */
    @ArchTest
    static final ArchRule 不得自建Redis客户端或连接工厂 = noClasses()
            .that().resideInAPackage("com.turbofeed.counter..")
            .should().dependOnClassesThat().belongToAnyOf(
                    Redisson.class,
                    Config.class,
                    RedisClient.class,
                    RedisClusterClient.class,
                    LettuceConnectionFactory.class,
                    LettuceClientConfiguration.class,
                    LettucePoolingClientConfiguration.class)
            .because("本服务曾自带 turbofeed.counter.redis.* 私有连接配置，与 Lettuce 侧形成两套参数，"
                    + "切集群时只改一侧 → 「计数 L2 读不到」的静默失效。客户端现在只由 tf-redis 装配。");

    /**
     * 不得依赖其它独立部署单元的代码（网关 / 引擎）。
     *
     * <p>与网关的耦合只应有两处：HTTP 增量上报接口，与共享契约 tf-shared。
     * 直接依赖对方的类会把两个部署单元焊死。</p>
     */
    @ArchTest
    static final ArchRule 不得依赖其它独立部署单元 = noClasses()
            .that().resideInAPackage("com.turbofeed.counter..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.turbofeed.gateway..",
                    "com.turbofeed.feedengine..")
            .because("tf-counter 与网关 / 引擎是独立部署单元：跨服务只走 HTTP / MQ，共享契约放 tf-shared。");

    /**
     * MQ 消费者必须恢复生产侧 traceId（M4）。
     *
     * <p><b>防的是什么</b>：本服务九成流量走 MQ，消费线程池化复用；新增
     * {@code RocketMQListener} 实现时漏写 {@code TraceContext.begin/end}，
     * 功能正常、编译通过、单测全绿，只有线上排查才发现该链路 traceId 为空或串号。</p>
     *
     * <p>rocketmq-spring-boot 2.3.5 无通用 MessageInterceptor 扩展点，tf-shared 又禁止
     * slf4j/MDC，统一装饰器无法下沉；因此用构建期规则把漏写挡住。</p>
     */
    @ArchTest
    static final ArchRule MQ消费者必须恢复MDC = classes()
            .that().implement(RocketMQListener.class)
            .should().dependOnClassesThat().haveSimpleName("TraceContext")
            .because("消费线程复用：不调用 TraceContext.begin/end 会让该链路日志丢失或串用 traceId，"
                    + "且不会有任何编译期 / 运行期报错，只能靠构建期规则兜住。");
}
