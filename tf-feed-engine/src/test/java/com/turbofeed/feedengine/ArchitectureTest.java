package com.turbofeed.feedengine;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.turbofeed.feedengine.contract.ItemViewCodec;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * tf-feed-engine 的架构门禁（M1 / 3.1）。
 *
 * <p>本服务是<b>独立部署单元</b>，且持有推荐流的读模型（时间线 ZSET）。这两点决定了下面两条规则：
 * Redis 客户端只能有一处装配、跨服务只能经 HTTP / MQ 通信。</p>
 */
@AnalyzeClasses(packages = "com.turbofeed.feedengine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Redis 客户端与连接工厂只能由 tf-redis 装配（R2 单一事实源）。
     *
     * <p><b>这条规则对应一次真实事故</b>：删除单机 Redis 切换集群时，只改了网关的拓扑配置，
     * 引擎仍连已下线的 {@code localhost:6379}，时间线读写静默失败 → <b>整个推荐流空白</b>。
     * 根因不是「忘了改」，而是「两个服务各配一套 Redis」这个结构本身。规则把它钉成硬约束：
     * 引擎里再出现自建客户端，构建即红。</p>
     *
     * <p><b>只禁构造与配置入口，不禁使用</b>：{@code VectorIndexWriter} 为 RediSearch 的
     * {@code FT.SEARCH} 自定义命令而使用 Lettuce 的编解码类型（{@code CommandArgs} /
     * {@code NestedMultiOutput}），这些是<b>命令层类型</b>而非客户端构造入口，不在禁止之列——
     * 它走的仍是 tf-redis 装配出来的连接工厂。</p>
     */
    @ArchTest
    static final ArchRule 不得自建Redis客户端或连接工厂 = noClasses()
            .that().resideInAPackage("com.turbofeed.feedengine..")
            .should().dependOnClassesThat().belongToAnyOf(
                    Redisson.class,
                    Config.class,
                    RedisClient.class,
                    RedisClusterClient.class,
                    LettuceConnectionFactory.class,
                    LettuceClientConfiguration.class,
                    LettucePoolingClientConfiguration.class)
            .because("Redis 拓扑必须由 tf-redis 作为单一事实源装配。引擎曾因「各配一套」在切集群后"
                    + "仍连已下线节点，导致整个推荐流空白——这类静默失效只能靠构建期规则拦住。");

    /**
     * 不得依赖其它独立部署单元的代码（网关 / 计数服务）。
     *
     * <p><b>防的是什么</b>：引擎与网关、计数服务各自独立部署、独立扩缩容。一旦直接依赖对方的类，
     * 就等于把两个部署单元焊死成一个——发布要同步、故障会传导、任何一方改签名都会直接编译不过。
     * 跨服务只能经 HTTP（{@code client} 包）或 MQ 通信，共享契约放 tf-shared。</p>
     */
    @ArchTest
    static final ArchRule 不得依赖其它独立部署单元 = noClasses()
            .that().resideInAPackage("com.turbofeed.feedengine..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.turbofeed.gateway..",
                    "com.turbofeed.counter..")
            .because("引擎 / 网关 / 计数服务是独立部署单元：直接依赖会把它们焊死成一个，"
                    + "发布要同步、故障会传导。跨服务只走 HTTP / MQ，共享契约放 tf-shared。");

    /**
     * 契约条目 {@code FeedItemView} 的反序列化只能经 {@link ItemViewCodec}（M3 契约旁路收口）。
     *
     * <p><b>为什么需要门禁而不是靠自觉</b>：M2/4.1 引入了版本协商，但它只挂在了一个入口上；
     * 引擎里另有 4 处裸 {@code readValue} 在解析同一个契约对象。这不是谁写错了，而是
     * 「加了协商」和「协商覆盖了所有解析点」本来就是两件事——前者靠一次提交，后者靠持续约束。
     * 没有门禁，下一次有人在新召回源里写一行 {@code readValue(json, FeedItemView.class)}，
     * 协商就又漏了一处，且<b>不会有任何报错</b>。</p>
     *
     * <p><b>规则为什么按方法签名而不是按参数值</b>：ArchUnit 读的是字节码，方法调用的实参
     * （那个 {@code FeedItemView.class} 字面量）在方法签名里不可见，无法精确判定「解析的是不是
     * 契约类」。退而求其次：禁止引擎内除 {@code ItemViewCodec} 外的任何类调用
     * ObjectMapper 的反序列化入口。代价是误伤面略大（新增其它 DTO 的同签名解析也会红），
     * 收益是引擎的序列化入口彻底收敛——与「Redis 客户端单一装配点」是同一个思路。</p>
     *
     * <p><b>误伤时的正确处置</b>：不是给本规则开白名单，而是把解析委托给 {@code ItemViewCodec}
     * （若它缺少对应形态，就给它加一个方法，让版本协商继续只存在一份）。</p>
     */
    @ArchTest
    static final ArchRule 契约条目只能由统一入口反序列化 = noClasses()
            .that().resideInAPackage("com.turbofeed.feedengine..")
            .and().areNotAssignableTo(ItemViewCodec.class)
            .should().callMethod(ObjectMapper.class, "readValue", String.class, Class.class)
            .orShould().callMethod(ObjectMapper.class, "readValue", String.class, JavaType.class)
            .orShould().callMethod(ObjectMapper.class, "convertValue", Object.class, JavaType.class)
            .because("FeedItemView 的反序列化只能经 ItemViewCodec，否则会绕过契约版本协商"
                    + "——M2 加协商时就有 4 处裸 readValue 漏网，且完全不报错。"
                    + "需要新形态的解析请给 ItemViewCodec 加方法，而不是在这里开白名单。");
}
