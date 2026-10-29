# 0075 · tf-benchmark 空壳补齐：五场景压测器 + Markdown 报告

- **状态**：已完成
- **优先级**：P2（评审文档 `project-review-2026-10.md` §4「`tf-benchmark` 空壳 + `tf-hotspot` 半残」）
- **关联**：0074（tf-hotspot 热/冷桶淘汰，同一 P2 条目）、0072（模块割裂校准）

## 为什么做

`tf-benchmark` 此前是**纯空壳**：5 个目录里只有 `.gitkeep`，零 `.java`。但它的 pom 描述写着

> 压测模块：数据构造器 + 压测器（五场景）+ Markdown 报告生成，一条命令产出可复现性能数据

**这不是「还没写」，是「承诺了却没写」**——比一个明显未启动的模块更容易误导：看 pom 的人会以为
性能数据有现成工具可复现，直到真的去用才发现是空的。评审给的两个选项是「补齐」或「从蓝图收缩」，
这里选补齐，因为性能回归是这个项目真实缺的一环（2.6 万行、22 个测试类，没有任何吞吐/延迟基线）。

## 设计

### 五场景对齐真实端点

| 场景 | 请求 | 性质 |
| --- | --- | --- |
| `feed` | `GET /api/v1/feed/recommended?page&size` | 读（推荐主链路，最重） |
| `playable` | `GET /api/v1/feed/playable?postIds` | 读（批量取播放地址） |
| `comment-list` | `GET /api/v1/comments?mediaId&size&mode` | 读（keyset 深分页，newest/hot 各半） |
| `like` | `POST /api/v1/feed/media/like` | **写**（计数 + 热 key） |
| `comment-publish` | `POST /api/v1/comments?mediaId&content` | **写**（敏感词 + 分片落库） |

压测不自造端点——自造端点测出来的是框架性能，不是业务性能。

### 关键决策

**① 成功判定必须读业务码，不能只看 HTTP 状态码。**
本项目鉴权失败返回的是 **HTTP 200 + `code=40101`**（既有事实，见 `.workbuddy/memory` 的环境坑）。
只看状态码的压测器会把它们全记成成功，产出一个漂亮的假 QPS 和 100% 成功率。因此判定固定为
`2xx && businessCode == 0`，且报告里对 401xx 单独出一条警示。

**② 闭合环路 + 可选节流。**
默认 N 线程各自「发一条→收到再发」，语义是「N 个并发用户」，与真实客户端一致。
`--target-rps` 只是**上限近似**（服务变慢时线程发不发得出那么多，不是真 open-loop）——
类注释里写清了这个边界，避免读者把节流结果当排队延迟用。

**③ 预热必须默认开。**
不预热时前几秒样本被 JIT、连接建立、服务端缓存冷启动污染，p99 会翻倍，测出来的是启动成本。
默认 5s 预热、样本全丢弃；若预热期成功率 < 50% 直接中止——配置错了（令牌失效/端点不存在）时
继续跑只会产出一份「全失败」的漂亮报告。

**④ 零第三方运行时依赖。**
刻意不引 Spring / Jackson：压测机必须比被测服务更轻，否则压测端自己的 GC 和反射会污染延迟分布。
JSON 只读两个字段（`code`、`accessToken`），手写 `JsonFields` 足够，顺带让离线构建不拉包。

**⑤ 统计热路径无锁。**
每线程独立 `LatencyCollector`（基本类型数组顺序追加，无装箱）与 `ScenarioContext`
（`ThreadLocalRandom`），结束后合并排序算分位。分位用**最近秩**不插值——读者关心「p99 那条请求多慢」，
不是一个不存在的插值点。

**⑥ 退出码给 CI 用。** `0` 达标 / `1` 成功率低于 99% / `2` 参数或登录失败 / `3` 报告写入失败。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `tf-benchmark/pom.xml` | 去掉无用 `spring-boot-starter`；加 test scope 的 `spring-boot-starter-test` + `junit-platform-launcher` |
| `.../benchmark/BenchOptions.java` | 新增：命令行解析 + 校验（时长支持 `30s/5m/1h`） |
| `.../benchmark/JsonFields.java` | 新增：极简字段提取（成员位置判定防误命中字符串值） |
| `.../benchmark/BenchmarkMain.java` | 新增：入口、退出码、用法说明 |
| `.../benchmark/runner/HttpProbe.java` | 新增：JDK HttpClient 探针，`2xx && code==0` 判定 |
| `.../benchmark/runner/RequestSpec.java` | 新增：待发请求模型 |
| `.../benchmark/runner/LatencyCollector.java` | 新增：无锁延迟采集 |
| `.../benchmark/runner/LoadRunner.java` | 新增：闭合环路并发执行 + 预热 + 聚合统计 |
| `.../benchmark/runner/BenchResult.java` | 新增：结果模型 + 错误分布 |
| `.../benchmark/scenario/Scenario.java` 等 3 个 | 新增：场景接口 / 每线程上下文 / 五场景注册表 |
| `.../benchmark/report/MarkdownReport.java` | 新增：Markdown 渲染 + 三条硬性判读 |
| `tf-benchmark/src/test/...` 3 个测试类 | 新增：21 用例（内嵌 HttpServer 端到端） |
| `docs/architecture/service-split.md` | §3 tf-benchmark 由「空壳占位」改为「已落地」；tf-hotspot 补淘汰策略与 10 单测 |
| `docs/changelog/0075-...md` | 本文件 |

## 验证证据

- `mvn -o -pl tf-benchmark -am test` → **BUILD SUCCESS，21 用例全绿**
  （`JsonFieldsTest` 10 / `LoadRunnerTest` 5 / `MarkdownReportTest` 6）。
- 其中 `businessErrorWithHttp200CountedAsFailure` 直接锁定本次核心缺陷：
  HTTP 200 + `code=40101` 必须计失败，否则成功率是假的。
- **真跑 CLI**（本地桩服务，已清理）：`feed` 场景 4 并发 3s → 27,828 请求 / 9,276 QPS /
  p99 1.09ms；`like` 写场景 2 并发 2s → 16,905 请求，报告正确标注「⚠️ 写场景」。
- 跑完连临时桩服务与脚本一并删除，未留残留进程。

## 风险与未决

- **写场景会污染环境**：`like` / `comment-publish` 产生真实数据。报告头部已强制标注，
  但仍需人工确认环境可清理；未加「只读保护」开关是刻意的——压测工具不该替使用者做判断。
- **压测结论只验证了压测器本身**：桩服务是本地 Python，未对真实 gateway 跑过基线。
  真实基线需要服务在线（Redis 集群 + MySQL + MinIO 全套），不在本次范围。
- **真 open-loop 未做**：需要独立调度线程池才能测「过载排队长度」，属容量规划范畴，暂不做。
- **数据构造器未做**：pom 描述里的「数据构造器」本次未实现——压测直接复用环境已有内容身份
  （`--post-ids`）。若要做「一键造 N 万条内容再压」，需要走上传接口，属独立工作项。
- 阈值（99% / 500ms）目前写死在 `MarkdownReport`，后续可提为参数；现在固化是刻意的，
  避免每次跑都调阈值把红线调成「刚好通过」。
