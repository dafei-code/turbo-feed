# 集中日志接线（阶段 A）：Loki + Fluent Bit + Grafana

> **状态：配置已落地，未做端到端运行验证。** 本轮按约定只完成文件与静态校验，没有启动容器、没有拉取镜像。真实容器 stdout 的自动采集必须在 Linux 宿主上验证，方法见 §5。

## 1. 解决什么问题

应用侧早已准备好格式条件：prod profile 下三份 `logback-spring.xml` 用 `LogstashEncoder` 把每行日志输出成 JSON 到 stdout，顶层带 `service`、`traceId`、`instanceId`、`cycleId`；`X-Trace-Id` 已贯通 gateway → feed-engine → counter 的 HTTP / MQ / 异步线程池。缺的只有「采集 → 聚合存储 → 检索 → 保留」这一段：日志此前仍物理分散在每个容器的 `json-file` 里，容器重建或扩缩容即丢。

本阶段补的正是这一段，且遵守两条约束：

| 约束 | 做法 |
| --- | --- |
| 日志系统不能成为应用启动/发布的依赖 | **零侵入**：不改 `docker-compose.prod.yml`、根 compose、任何 Java 代码；应用继续写 stdout，采集器只读宿主上的 `json-file` |
| 改动必须可回滚 | 独立编排 `name: turbo-feed-observability`，不与业务共用网络；回滚 = `down -v` + 删目录 |

## 2. 文件清单

```
deploy/observability/
├── docker-compose.observability.yml        # 独立编排：Loki / Fluent Bit / Grafana
├── loki/loki-config.yml                    # 单机 filesystem、168h 保留、compactor
├── fluent-bit/
│   ├── fluent-bit.conf                     # tail → 二次解析 → Loki output
│   └── parsers.conf                        # docker_json（外层）/ app_json（里层）
└── grafana/provisioning/
    ├── datasources/loki.yml                # uid 固定为 loki
    ├── dashboards/dashboards.yml
    ├── dashboards/turbo-feed-logs.json     # 速率 + 按 traceId 串链路 + 全量日志
    └── alerting/rules.yml                  # 2 条规则（Grafana ≥ 10.1）
```

应用侧 `customFields.service` 的三个取值：`tf-gateway`、`tf-feed-engine`、`tf-counter`。

### 为什么要解析两次

Docker 把应用 stdout 当作**字符串**塞进 `log` 字段，不是嵌套对象：

```json
{"log":"{\"service\":\"tf-gateway\",\"level\":\"INFO\",\"traceId\":\"...\"}",
 "stream":"stdout","time":"2026-11-16T10:00:00.000+0800"}
```

`[INPUT]` 的 `docker_json` 拆外层，`[FILTER] parser` 的 `app_json` 把 `log` 里的 JSON 提升为顶层键，Loki output 才能用 `Label_Keys $service,$level` 建标签。只解析外层的话，`{service="tf-gateway"}` 会一条都查不到，而且不报任何错。

## 3. 标签基数：最容易踩的坑

**只有低基数字段能做 Loki label**：静态 `job=turbofeed`，动态 `service`（3 个值）、`level`（约 5 个值）。

`traceId`、`userId`、`postId`、`instanceId` 一律**不做 label**，留在日志行里用 `| json` 过滤。把它们做成 label 会让每个请求生成一条新序列，Loki 的索引直接被打爆——这是集中日志上线后最典型的自伤方式。`loki-config.yml` 里 `max_label_names_per_series: 12` 是兜底，不是许可。

## 4. 启动

前置条件：

- Linux 宿主，且 `/var/lib/docker/containers` 可读（采集器以 `user: "0:0"` 运行）。
- 应用以 **prod profile** 启动（prod compose 已设 `SPRING_PROFILES_ACTIVE: prod`）；非 prod 是纯文本 console 日志，仍能被 `{job="turbofeed"}` 查到，但没有 `service` / `traceId` 结构化字段。
- 应用 compose 保留 `json-file` 轮转（prod compose 已有 50m×5）。

```bash
export TURBOFEED_GRAFANA_ADMIN_PASSWORD='<强口令>'   # 必填，仓库默认配置不放口令
docker compose -f deploy/observability/docker-compose.observability.yml up -d

curl -fsS http://localhost:3100/ready                # Loki 就绪
curl -fsS http://localhost:2020/api/v1/health        # Fluent Bit 健康
# Grafana: http://localhost:3000   admin / 上面导出的口令
```

可选环境变量：`TURBOFEED_LOKI_HOST_PORT`（3100）、`TURBOFEED_GRAFANA_HOST_PORT`（3000）、`TURBOFEED_FLUENTBIT_HTTP_PORT`（2020）、`TURBOFEED_DOCKER_CONTAINERS_DIR`（默认 `/var/lib/docker/containers`）、`TURBOFEED_GRAFANA_ADMIN_USER`（默认 admin）。

> 镜像 tag（`grafana/loki:3.3.2`、`fluent/fluent-bit:3.2.4`、`grafana/grafana:11.4.0`）本轮**未经拉取验证**，起容器前请按实际环境确认或调整。

## 5. 验证口径

### 5.1 静态校验（本轮已完成，见交付说明）

- `docker compose -f deploy/observability/docker-compose.observability.yml config` 可解析。
- 所有 YAML 可被 YAML 解析器加载；dashboard JSON 合法。
- `Label_Keys` 不含 `traceId` / `userId` 等高基数字段。

### 5.2 端到端（未执行，需在 Linux 宿主补）

1. 起应用（prod profile）+ 本编排。
2. 发一条带 `X-Trace-Id` 的请求，或直接用 Loki push API 注入样本：

```bash
curl -sS -H 'Content-Type: application/json' \
  -X POST http://localhost:3100/loki/api/v1/push \
  -d '{"streams":[{"stream":{"job":"turbofeed","service":"tf-gateway","level":"INFO"},
      "values":[["'"$(date +%s)000000000"'",
      "{\"service\":\"tf-gateway\",\"level\":\"INFO\",\"traceId\":\"e2e-verify-0001\",\"message\":\"probe\"}"]]}]}'

curl -sS -G http://localhost:3100/loki/api/v1/query \
  --data-urlencode 'query={service="tf-gateway"} | json | traceId="e2e-verify-0001"' \
  --data-urlencode 'limit=10'
```

3. Grafana → `turbo-feed` 文件夹 → 「turbo-feed 应用日志」面板，把 `traceId` 粘进变量框，确认能拉出跨服务日志。
4. 检查两条告警规则在 Grafana Alerting 里出现且状态正常；若版本不支持文件 provisioning，按 §7 的 LogQL 手工建。

**macOS Docker Desktop 说明**：容器日志在 VM 内，宿主没有 `/var/lib/docker/containers`，挂载后采集器读不到应用日志。本机只能验证 Loki / Grafana 与上面的 HTTP 注入样本；要验证真实 stdout 自动采集，请把 `TURBOFEED_DOCKER_CONTAINERS_DIR` 指向一个含 `*-json.log` 的目录，或直接在 Linux 上跑。

## 6. LogQL 速查

| 目的 | 查询 |
| --- | --- |
| 某个服务的全部日志 | `{service="tf-gateway"}` |
| 三个服务一起看 | `{service=~"tf-.*"}` |
| ERROR 日志 | `{service=~"tf-.*"} \| json \| level="ERROR"` |
| **按 traceId 串全链路** | `{service=~"tf-.*"} \| json \| traceId="<32位hex>"` |
| 某实例的日志 | `{service=~"tf-.*"} \| json \| instanceId="tf-gateway-7d9c"` |
| 定时任务的同一轮调度 | `{service=~"tf-.*"} \| json \| cycleId="<id>"` |
| 5 分钟 ERROR 速率 | `sum(count_over_time({service=~"tf-.*"} \| json \| level="ERROR" [5m]))` |
| 结构化日志失效（无 service） | `sum(count_over_time({job="turbofeed",service=""} [5m]))` |
| 中间件日志兜底 | `{job="turbofeed",service=""}` |

`traceId` 来自 `X-Trace-Id` 响应头或服务端 MDC；`cycleId` 出现在 Outbox / Counter flush 等定时调度日志里。

## 7. 保留与告警

- **保留**：`limits_config.retention_period: 168h`（7 天，与 behavior-log 本地保留对齐），由 `compactor.retention_enabled: true` 实际删除，`retention_delete_delay: 2h`。调长前先估算磁盘：日志量 × 天数 × 压缩比，Loki 用的是本地 filesystem 卷。
- **告警规则**（`alerting/rules.yml`，只定义规则，不绑定通知渠道）：

| 规则 | 判据 | 阈值 / 持续 | 为什么是它 |
| --- | --- | --- | --- |
| 应用 ERROR 日志突增 | `sum(count_over_time({service=~"tf-.*"} \| json \| level="ERROR" [5m]))` | > 20 / 5m | 最直接的线上故障信号 |
| 结构化日志失效 | `sum(count_over_time({job="turbofeed",service=""} [5m]))` | > 50 / 5m | `service` 标签缺失说明 prod profile 没生效或二次解析失败，即「日志系统自己悄悄失效」——项目里反复强调要先让失效可见的那类故障 |

第二规则刻意没用「traceId 缺失率」：定时任务与启动期日志本来就没有 `traceId`，会持续误报。contact point / notification policy 由部署方接。

## 8. 回滚

```bash
docker compose -f deploy/observability/docker-compose.observability.yml down -v
rm -rf deploy/observability            # 应用侧完全没被改过，无需恢复
```

`down -v` 会删掉 Loki / Grafana 的命名卷（含已采集日志与面板改动），执行前确认不再需要。

## 9. 已知边界与后续

| 边界 | 说明 |
| --- | --- |
| 单机 Loki | filesystem 单副本，不是高可用日志集群；宿主机故障即全丢。多机房需对象存储后端 + 分布式 Loki |
| 不是标准 tracing | `traceId` 仍是自研的日志关联 ID，没有 Span / 耗时 / 调用拓扑 / 采样 / Collector / W3C `traceparent`。接 OTel 是独立后续项，本阶段刻意不做 |
| behavior-log 不并入 | 行为明细是训练样本 ODS（本地 JSONL + 每小时 gzip 归档到 MinIO），与集中日志是两条独立管道，不要混 |
| 采集器故障 | Fluent Bit 开了磁盘缓冲 + `Retry_Limit False`，Loki 短暂不可用不应丢日志；但采集器自身 `logging: none` 避免自采集环路，其排障日志需从 `docker inspect` / 进程 stdout 另取 |
| 未验证项 | 镜像 tag、真实 stdout 采集、Grafana provisioning 版本兼容性、告警规则实际评估，均需一次端到端运行 |

下一阶段（阶段 B）处理多副本状态一致性：词库 / 白名单跨实例 epoch 与广播失效、counter 共享缓冲与 ShedLock、workerId 租约全开、热点榜广播。**先有集中日志，B 上线后的收敛行为才可验证**——这也是先做 A 的原因。
