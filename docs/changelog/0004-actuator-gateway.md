# 0004 · 三个服务统一 actuator 健康检查

- 日期：2026-08-28
- 类型：feat(gateway)
- 关联方案：[服务拆分方案](../architecture/service-split.md)

## 动机

拆出三个独立部署单元后，需要统一的探活端点（K8s liveness / readiness probe），并为 Phase 2 Prometheus 指标采集预留入口。tf-counter / tf-feed-engine 在 0002 / 0003 中已带 actuator，本次补齐 tf-gateway 保持三服务对齐。

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-gateway/pom.xml` | 新增 `spring-boot-starter-actuator` |

## 说明

- 端点暴露范围：健康类（`health` / `info`），与引擎服务 yml 中的配置一致；gateway 尚无 yml 级暴露配置，默认仅 health 可访问，行为对齐；
- 接 Prometheus 时统一在各自 yml 开放 `prometheus` 端点（届时新增 changelog）。

## 验证

```bash
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## 回滚

从 `tf-gateway/pom.xml` 删除 actuator 依赖。
