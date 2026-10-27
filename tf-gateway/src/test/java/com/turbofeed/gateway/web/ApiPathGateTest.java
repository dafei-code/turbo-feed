package com.turbofeed.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-3 的构建期门禁：<b>所有对外控制器必须挂在版本前缀下</b>。
 *
 * <p><b>防的是什么</b>：新增一个控制器时忘记带 {@code ApiVersion.V1} 前缀——它照样能跑、照样 200，
 * 只是从此成为一条「不版本化的接口」：以后真要改它的契约，没有任何老客户端保护。
 * 这类问题不会让编译失败，也没有任何既有测试会红，只能靠这条门禁。
 *
 * <p><b>为什么不用 ArchUnit</b>：本类需要的是「扫描真实类 + 读注解字面量」，
 * 用 Spring 自己的类路径扫描即可，不必再引入 ArchUnit 的注解代理 API（版本差异较大）。
 * 语义与 ArchUnit 规则等价：违反即红。</p>
 *
 * <p><b>反空洞性</b>：先断言「确实扫到了一批控制器」（{@code assertFalse(isEmpty)} + 数量下限）。
 * 若包名被重构导致扫描为空，这条断言会先红——否则本测试会变成一句永远通过的废话。</p>
 */
class ApiPathGateTest {

    /** 当前控制器数量的下限（写死一个下界而不是精确值：新增控制器不需要改这个数字，删到异常才红）。 */
    private static final int MIN_CONTROLLERS = 17;

    @Test
    void 所有控制器都必须声明版本前缀或内部前缀() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<BeanDefinition> candidates = new ArrayList<>(scanner.findCandidateComponents("com.turbofeed.gateway"));
        assertFalse(candidates.isEmpty(), "扫描不到任何 @RestController —— 包名被改过？门禁已失效，先修扫描再谈通过");
        assertTrue(candidates.size() >= MIN_CONTROLLERS,
                "只扫到 " + candidates.size() + " 个控制器（预期 ≥ " + MIN_CONTROLLERS + "）：扫描范围被破坏，门禁不可信");

        List<String> violations = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            String className = candidate.getBeanClassName();
            RequestMapping mapping = null;
            try {
                mapping = Class.forName(className).getAnnotation(RequestMapping.class);
            } catch (ClassNotFoundException e) {
                violations.add(className + "：类加载失败（" + e.getMessage() + "）");
                continue;
            }
            String[] paths = mapping == null ? new String[0] : mapping.value();
            if (paths.length == 0) {
                violations.add(className + "：未声明类级 @RequestMapping（端点会挂在根路径）");
                continue;
            }
            String prefix = paths[0];
            boolean ok = prefix.startsWith(ApiVersion.V1 + "/")
                    || prefix.startsWith(ApiVersion.INTERNAL + "/");
            if (!ok) {
                violations.add(className + "：@RequestMapping(\"" + prefix + "\") 必须以 "
                        + ApiVersion.V1 + "/ 或 " + ApiVersion.INTERNAL + "/ 开头");
            }
        }
        assertEquals(List.of(), violations,
                "以下控制器没有挂在版本前缀下（新接口一律挂 " + ApiVersion.V1 + "，内部接口挂 "
                        + ApiVersion.INTERNAL + "）：\n" + String.join("\n", violations));
    }
}
