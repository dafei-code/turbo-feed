package com.turbofeed.gateway.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 标记需要 Docker 的集成测试（如 Testcontainers 起的真实 MySQL / Redis）。
 *
 * <p><b>为什么需要它</b>：本机未起 Docker 时，Testcontainers 会抛
 * {@code IllegalStateException: Could not find a valid Docker environment} 导致测试<b>失败</b>，
 * 而非跳过。这会让「本地没 Docker 的开发者」跑 {@code mvn test} 全盘红，误导。
 * 用本注解标记后，无 Docker 时测试被<b>跳过（disabled）</b>——本地只跑 Mockito 单测（绿），
 * CI（有 Docker）跑全量集成测。</p>
 *
 * <p><b>不用 testcontainers 内部 API 探测</b>：直接探 {@code DOCKER_HOST} / 默认 socket / Windows 管道，
 * 避免对该内部类的版本耦合（不同 testcontainers 版本包路径有漂移）。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDockerCondition.class)
public @interface RequiresDocker {
}

/** 执行条件：以「轻量探测」判断本机 Docker 可用性（与 Testcontainers 自身探测逻辑一致）。 */
class RequiresDockerCondition implements ExecutionCondition {

    /**
     * 显式开关：必须 {@code TF_IT_DOCKER=true} 才跑 Testcontainers 集成测。
     *
     * <p><b>为什么不能只靠自动探测</b>：2026-10-20 实测本机——{@code docker info} 正常
     * （CLI 可用），但面向 HTTP API 的 Unix socket 被 Docker Desktop 代理拦成
     * {@code 400 Bad Request}，Testcontainers 的 Java 客户端连不上。此时任何基于 CLI 的
     * 探测都会误判「可用」，结果是集成测不跳过、直接抛
     * {@code Could not find a valid Docker environment}，把 {@code mvn test} 打成红的。</p>
     *
     * <p>因此改为「显式开启 + 探测通过」双条件：默认跳过（本地永远绿），
     * CI 或确认可用的机器上设 {@code TF_IT_DOCKER=true} 即跑。</p>
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (!"true".equalsIgnoreCase(System.getenv("TF_IT_DOCKER"))) {
            return ConditionEvaluationResult.disabled(
                    "未显式开启集成测（设 TF_IT_DOCKER=true 运行；避免桌面代理导致的 Docker 误判）");
        }
        if (DockerEnvironment.available()) {
            return ConditionEvaluationResult.enabled("Docker 可用，运行集成测试");
        }
        return ConditionEvaluationResult.disabled("Docker daemon 不可达 — 跳过集成测试");
    }
}

/**
 * Docker 可用性探测：<b>真 ping daemon</b>，而不是「看 socket 文件在不在」。
 *
 * <p><b>为什么必须改（2026-10-20 实测踩到）</b>：Docker Desktop 退出后，
 * {@code /var/run/docker.sock}（以及 Desktop 自己的 socket 文件）<b>仍然存在</b>，
 * 但 API 会返回 {@code 400 Bad Request}（{"Containers":0,"ServerVersion":""}）。
 * 只看文件存在 → 误判「Docker 可用」→ 集成测不跳过 → {@code mvn test} 报
 * {@code Could not find a valid Docker environment} <b>全盘红</b>，而这恰恰是
 * 「本地没起 Docker 就该跳过」这个守卫要防的事。</p>
 *
 * <p><b>探测方式</b>：跑 {@code docker info --format {{.ServerVersion}}}，3 秒超时，
 * 退出码 0 且输出非空才算可用。它天然尊重 {@code DOCKER_HOST} 与 docker context，
 * 比手写 socket 路径判断更准（Desktop 的 socket 实际在
 * {@code ~/Library/Containers/com.docker.docker/Data/docker-cli.sock}，不在 {@code /var/run}）。</p>
 *
 * <p><b>结果缓存</b>：探测一次约几十毫秒到 3 秒，每个测试类都探一遍会拖慢构建，
 * 故用 {@code static} 缓存（同一 JVM 内 Docker 可用性不变）。</p>
 */
final class DockerEnvironment {

    /** null = 未探测；TRUE/FALSE = 已探测结果（JVM 内缓存，避免每个测试类重复等超时）。 */
    private static volatile Boolean cached;

    private DockerEnvironment() {
    }

    static boolean available() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (DockerEnvironment.class) {
            if (cached == null) {
                cached = probe();
            }
            return cached;
        }
    }

    private static boolean probe() {
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) {
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return !out.isEmpty();
        } catch (Exception e) {
            // docker 命令不存在 / 被中断 / 无权限：一律按不可用处理（跳过而非失败）
            return false;
        }
    }
}
