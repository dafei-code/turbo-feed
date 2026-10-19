package com.turbofeed.gateway.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Paths;

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
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (DockerEnvironment.available()) {
            return ConditionEvaluationResult.enabled("Docker 可用，运行集成测试");
        }
        return ConditionEvaluationResult.disabled(
                "Docker 不可用 — 跳过集成测试（需在 CI 或本地起 Docker 后运行；单测不受影响）");
    }
}

/** 移植自 Testcontainers 的 Docker 可用性探测：DOCKER_HOST / 默认 Unix socket / Windows 命名管道。 */
final class DockerEnvironment {
    private DockerEnvironment() {
    }

    static boolean available() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            return true;
        }
        if (Files.exists(Paths.get("/var/run/docker.sock"))) {
            return true;
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")
                && Files.exists(Paths.get("\\\\.\\pipe\\docker_engine"))) {
            return true;
        }
        return false;
    }
}
