package com.turbofeed.shared;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * tf-shared 的架构门禁（M1 / 3.1）。
 *
 * <p><b>为什么第一条规则最重要</b>：tf-shared 是被<b>每一个服务内嵌</b>的共享库（网关、引擎、
 * 计数、热点都依赖它）。一旦有人为了方便往里塞一个 Spring 注解或 Jackson 依赖，这份框架耦合
 * 就会顺着依赖图扩散到所有服务，届时再想拆，成本是「改所有服务」。所以「零第三方依赖」
 * 不是洁癖，是这条依赖链上唯一便宜的防线——放在构建期，谁加谁红。</p>
 *
 * <p>放心的部分：允许 {@code java..}（JDK 自带）与 {@code com.turbofeed.shared..}（自身）。
 * 也就是说本库只准用 JDK 标准库，连 {@code org.slf4j} 都不行——日志由调用方打。</p>
 */
@AnalyzeClasses(packages = "com.turbofeed.shared", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 共享库不得依赖任何第三方框架：只允许 JDK 与自身包。
     *
     * <p>允许 {@code java..} 而非 {@code java.lang..}：{@code java.util} / {@code java.time} /
     * {@code java.nio} 等都是正常建模所需，不加限制。</p>
     */
    @ArchTest
    static final ArchRule 共享库只准依赖JDK与自身 = noClasses()
            .that().resideInAPackage("com.turbofeed.shared..")
            .should().dependOnClassesThat().resideOutsideOfPackages("com.turbofeed.shared..", "java..")
            .because("tf-shared 被所有服务内嵌：任何第三方依赖都会顺着依赖图扩散到每个服务，"
                    + "届时再拆的成本是「改所有服务」。只允许 JDK 标准库。");

    /**
     * 共享库不得反向依赖任何上层服务（网关 / 引擎 / 计数 / 热点 / Redis 接入层）。
     *
     * <p>与上一条的差别：上一条已经禁止了所有非 JDK 依赖，这条是<b>显式点名</b>——
     * 违规信息里会直接出现「com.turbofeed.shared 依赖了 com.turbofeed.gateway」，
     * 比一句「依赖了外部包」更容易定位；同时它也是将来万一给 shared 开白名单时的兜底。</p>
     */
    @ArchTest
    static final ArchRule 共享库不得反向依赖上层服务 = noClasses()
            .that().resideInAPackage("com.turbofeed.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.turbofeed.gateway..",
                    "com.turbofeed.feedengine..",
                    "com.turbofeed.counter..",
                    "com.turbofeed.hotspot..",
                    "com.turbofeed.redis..")
            .because("共享库位于依赖图最底层，反向依赖上层服务会立刻形成循环依赖。");
}
