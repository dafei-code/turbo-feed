package com.turbofeed.gateway;

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
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * tf-gateway 的架构门禁（M1 / 3.1）。
 *
 * <p><b>为什么把架构决策写成测试</b>：本项目踩过的坑有个共同形状——「约定写在文档里，代码里没人执行」。
 * Redis 拓扑曾经两个服务各配一套，切集群时只改网关、引擎静默失败；双投递通道的副作用各写一遍，
 * 漏写不报错，只表现为静默失效。这类缺陷<b>不会让编译失败、不会让单测变红</b>，只能靠人肉 review
 * 兜住，而人肉 review 在 2 万行规模下必然漏。放在构建期的规则则是：谁违反，谁的 PR 红。</p>
 *
 * <p>规则的选择标准：<b>只写「违反即缺陷」的规则</b>，不写审美偏好。每条都注明了它防的是哪个
 * 已知失败模式；说不出失败模式的规则不写（那只会把构建变成麻烦）。</p>
 */
@AnalyzeClasses(packages = "com.turbofeed.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Controller 不得直接依赖 Repository：数据访问必须经 Service。
     *
     * <p><b>防的是什么</b>：Controller 直连仓储后，事务边界、权限校验、缓存失效这些横切逻辑
     * 就有两个地方可写——写在 Controller 的那份，任何走 Service 的调用路径都不会经过它。
     * 这正是「同一副作用两条路径各写一遍」的入口。</p>
     *
     * <p><b>本规则零例外</b>：规则初建时 {@code AdminMediaController} 曾直连
     * {@code MediaJdbcRepository#listPendingGlobal}，当时以白名单保留、并把原因写进
     * {@code because} 让债务显形；随后该查询已下沉为
     * {@code MediaReviewService#listPendingQueue}，白名单随即移除。
     * <b>保留这条记录是为了说明处置方式</b>：白名单是「已知的债 + 排期」，不是永久豁免。</p>
     */
    @ArchTest
    static final ArchRule controller不得直接依赖repository = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("数据访问必须经 Service 收敛：事务边界 / 权限校验 / 缓存失效一旦在 Controller 里"
                    + "再写一遍，任何走 Service 的路径都会绕过它（本项目已付过学费的失败模式）。"
                    + "本规则零例外——需要新查询就在 Service 加方法，不要回来开白名单。");

    /**
     * Service 不得反向依赖 Controller（分层单向：Controller → Service → Repository）。
     *
     * <p><b>防的是什么</b>：反向依赖一旦出现，HTTP 层（参数绑定、鉴权注解、序列化）就渗进了业务层，
     * 业务逻辑从此无法在非 Web 场景（定时任务、MQ 消费者）下复用——而这些场景本项目都有。</p>
     */
    @ArchTest
    static final ArchRule service不得反向依赖controller = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .because("分层必须单向。反向依赖会把 HTTP 层渗进业务层，业务从此无法在定时任务 / MQ 消费者中复用。");

    /**
     * Repository 不得依赖 Service Bean（允许依赖 domain 模型与枚举）。
     *
     * <p>注意口径：本规则只拦 {@code @Service} 标注的<b>业务 Bean</b>，不拦 {@code service.*} 包下
     * 的领域模型 / 枚举（如 {@code MediaStatus}、{@code ReviewTask}）——这些类确实放在 service 包里，
     * 但它们是数据契约而非行为。规则要防的是「仓储反过来调用业务服务」形成循环。</p>
     */
    @ArchTest
    static final ArchRule repository不得依赖serviceBean = noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().areAnnotatedWith(Service.class)
            .because("Repository 处于分层底部，反向调用 Service 会形成循环依赖，"
                    + "且让事务边界变得不可推理（service 调 repository、repository 又回调 service）。");

    /**
     * Redis 客户端与连接工厂只能由 tf-redis 装配，业务模块不得自建（R2 单一事实源）。
     *
     * <p><b>防的是什么</b>：本项目因为「两个服务各配一套 Redis」，切集群时只改了网关、引擎仍连
     * 已下线节点，时间线读写静默失败、整个推荐流空白。tf-counter 也曾经自带 Redisson 配置空间。
     * 一旦有人再 {@code new} 一个客户端出来，那套客户端就会绕开统一拓扑悄悄漂移。</p>
     *
     * <p><b>只禁「构造与配置入口」，不禁「使用」</b>：注入 {@code StringRedisTemplate} 或
     * {@code RedissonClient} 来读写是允许的；被禁的是把客户端<b>造出来</b>的那些类
     * （{@code Redisson.create} / {@code Config} / {@code RedisClient} / {@code LettuceConnectionFactory}）。
     * 这样既保住单一事实源，也不误伤为 RediSearch 自定义命令而使用 Lettuce 编解码类型的场景。</p>
     */
    @ArchTest
    static final ArchRule 不得自建Redis客户端或连接工厂 = noClasses()
            .that().resideInAPackage("com.turbofeed.gateway..")
            .should().dependOnClassesThat().belongToAnyOf(
                    Redisson.class,
                    Config.class,
                    RedisClient.class,
                    RedisClusterClient.class,
                    LettuceConnectionFactory.class,
                    LettuceClientConfiguration.class,
                    LettucePoolingClientConfiguration.class)
            .because("Redis 拓扑必须由 tf-redis 作为单一事实源装配。业务模块自建客户端 = 又一套连接参数，"
                    + "切换集群/哨兵时必然只改一处、另一处静默失效（本项目已因此导致整个推荐流空白）。");
}
