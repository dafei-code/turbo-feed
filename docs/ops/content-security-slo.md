# 内容安全（敏感词）SLO 与容量基线

- 建立日期：2026-11-09
- 定位：Phase 0「先建立度量」的验收口径。**后续所有优化是否值得做，都以本文档的指标为准**，而不是凭架构直觉。
- 关联：`ContentSecurityService`（检测入口）、`docs/adr/0013`、`docs/adr/0014`

## 一、为什么先定 SLO 而不是先优化

内容安全的优化有个陷阱：**「让它更快」和「让它更准」都可以无限投入**。没有量化目标时，最容易被两类错误决策拖走——

1. **过早换重结构**：还没测出瓶颈就上双数组 Trie / 独立服务，复杂度先付款、收益未验证；
2. **优化错目标**：花大力气把 AC 匹配从 1.36µs 压到 0.8µs（占总耗时不到 10%），却放过了占大头的逐字符 NFKC。

SLO 的作用是把「该不该动手」变成可判断的问题。本文档定义阈值，`docs` 内不写实现方案（实现见演进计划）。

## 二、核心 SLO

| 编号 | 指标 | 目标 | 判据来源 | 违反后果 |
|---|---|---|---|---|
| SLO-1 | 检测 P99 延迟 | ≤ **2ms**（文本 ≤ 2048 字符） | `content_security_check_duration_nanos` | 写路径用户可感知卡顿 |
| SLO-2 | 检测 P50 延迟 | ≤ **300µs** | 同上 | 说明有系统性开销，需定位 |
| SLO-3 | 词库变更生效时延 | ≤ **35s**（30s 定时 + 5s 余量） | `content_security_dict_age_seconds` | 敏感词不能及时生效 |
| SLO-4 | 词库可用性 | 已加载条数 > 0（生产环境） | `content_security_dict_size` | **过滤整体失效**（最严重） |
| SLO-5 | 检测可用性 | ≥ 99.99%（fail-open 时需可见） | 错误日志 + `check_total` 计数突降 | 内容未经检测即发布 |
| SLO-6 | 误杀可解释性 | 每次拒绝可在日志中定位命中词与分类 | 服务端日志 | 运营无法判断是否误杀 |

**SLO-4 是红线**：`content_security_dict_size == 0` 意味着所有检测都放行，且从接口层面看不出异常。这是本项目历史上踩过的坑（空 Trie 静默失效），因此单独列为 SLO 而不是当作普通指标。

## 三、指标清单与采集方式

已接入 Micrometer → `/actuator/prometheus`（Prometheus 基建在 `tf-gateway/pom.xml` 既有）。

| 指标名 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `content_security_check_total` | Counter | `scene`（NICKNAME/CAPTION/COMMENT）、`action`（PASS/REVIEW/LIMIT/BLOCK/WHITELISTED） | 检测量、命中分布、豁免比例 |
| `content_security_check_duration_nanos` | DistributionSummary | — | P50/P95/P99 延迟 |
| `content_security_dict_size` | Gauge | — | 已加载词条数（0 = 失效） |
| `content_security_dict_age_seconds` | Gauge | — | 距上次成功重建的秒数（持续增大 = 热更新卡住） |

### 建议的告警规则（Prometheus）

```yaml
- alert: ContentSecurityDictEmpty
  expr: content_security_dict_size == 0
  for: 5m
  severity: critical
  summary: 敏感词库为空，内容过滤整体失效

- alert: ContentSecurityDictStale
  expr: content_security_dict_age_seconds > 300
  for: 10m
  severity: warning
  summary: 词库超过 5 分钟未刷新，热更新可能卡住

- alert: ContentSecurityP99High
  expr: histogram_quantile(0.99, rate(content_security_check_duration_nanos_bucket[5m])) > 2000000
  for: 10m
  severity: warning
  summary: 检测 P99 超过 2ms SLO

- alert: ContentSecurityNoHits
  expr: rate(content_security_check_total{action="BLOCK"}[1h]) == 0 and content_security_dict_size > 0
  for: 1h
  severity: warning
  summary: 词库非空但一小时零拦截，可能存在绕过
```

最后一条（`ContentSecurityNoHits`）价值最高：它捕获的是"看起来一切正常、实际全被绕过"的静默失效——与 SLO-4 是同一类风险的两个侧面。

## 四、容量基线（压测）

`tf-benchmark` 场景 `comment-publish` 已调整为约 200 字符的中英混排正文（接近真实评论中位长度）。

**为什么必须调长**：改造前正文只有约 8 字符，检测链路被极快扫过，测出的数字无法反映真实成本——尤其 `TextNormalizer` 当前逐字符调 NFKC，成本随长度线性增长。用短文本压测会系统性低估检测开销。

跑法（需先启动网关与引擎）：

```bash
java -cp tf-benchmark/target/classes:... com.turbofeed.benchmark.BenchmarkMain \
  --scenario comment-publish --duration 60 --concurrency 32
```

**基线记录要求**（每次压测填一行，形成时间序列）：

| 日期 | 词库条数 | 并发 | QPS | P50 | P95 | P99 | 备注 |
|---|---|---|---|---|---|---|---|
| _待补_ | | | | | | | Phase 0 建立，首次基线 |

> ⚠️ 首次基线必须在本机真实环境跑出后填入。**不要凭 AC 单测的微秒数推算端到端 P99**——那不含归一化、DB 词库加载、Spring 拦截器与网络开销。

## 五、SLO 与演进阶段的对应

这是本文档最实际的价值：**明确"什么信号触发下一阶段"**。

| 阶段 | 触发条件（来自本文档指标） | 该阶段做什么 |
|---|---|---|
| Phase 1（10× 规模） | 当前即触发（工程保障不足） | NFKC 整串化、两级缓存、检测限流、集成测试、申诉闭环 |
| Phase 2（100× 词库） | `content_security_dict_size` > **100,000** 或 P99 超 SLO 且定位到匹配层 | AC→DAT、词库分片、增量构建 |
| Phase 3（形态切换） | 产品需覆盖图片/视频/语音 | 多模态、规则+模型双引擎 |
| 检测服务独立化 | 检测 CPU 占网关 > **30%** 或 P99 因业务争抢抖动 | 抽 `tf-content-security` |

**关键纪律**：不要在指标未触发前提前做下一阶段的重结构改造。现有 `AhoCorasick` 类注释已论证过这个顺序（"先让失效可见 → 再增量更新 → 最后才是内存结构"），本文档把该判断变成可执行的触发规则。

## 六、不在本文档范围内的

- **命中率/误杀率的绝对值**：这类目标依赖具体业务与词库质量，无法在项目层面设固定阈值。改为通过 `check_total` 的 `WHITELISTED` 占比做趋势监控（占比持续上升 = 白名单过宽或词库过严）。
- **多模态检测 SLO**：属于 Phase 3，届时单独立文档。
- **词库内容质量**：运营数据，不属工程质量指标。