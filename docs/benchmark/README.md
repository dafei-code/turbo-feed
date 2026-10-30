# 压测报告归档

本目录存放 **tf-benchmark 实跑产出的原始报告**（每次压测一轮 = 一个文件），以及按轮次汇总的判读结论。
原始报告由 `tf-benchmark` 自动生成到 `target/benchmark/`（gitignored），**有价值的那一轮才归档到这里**——
不归档的跑分等于没跑，归档了的跑分才是容量基线。

> 报告正文是机器生成的（压测条件 / 吞吐 / 延迟分位 / 自动判读）。本 README 只补机器算不出来的部分：
> **这组数字能说明什么、不能说明什么**。

---

## 第 1 轮 · 2026-10-30 · feed 场景阶梯压测

### 1.1 结论先行

| 问题 | 结论 |
| --- | --- |
| 2000 QPS 扛得住吗 | **扛得住**，成功率 100%，p99 8.47ms |
| 找到容量拐点了吗 | **没有**，五档延迟几乎持平（p99 7.6~13.3ms），未见退化拐点 |
| 瓶颈在哪 | **压测机自身**（load 8.66 / 10 核），不是被测服务 |
| 能当生产容量吗 | **不能**，单机同进程，见 §1.4 |

### 1.2 阶梯数据（`scenario=feed`，链路 gateway → engine → Redis）

| 文件 | 并发 | 目标 QPS | 实际 QPS | 成功率 | p50 | p95 | p99 | max | 压测后 load |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [100qps](./2026-10-30-feed-100qps.md)   | 4  | 100  | 100.0  | 100% | 4.69 ms | 8.06 ms | 13.29 ms | 18.08 ms | ~5.04 |
| [300qps](./2026-10-30-feed-300qps.md)   | 6  | 300  | 299.9  | 100% | 3.14 ms | 4.92 ms | 8.35 ms  | 39.43 ms | 4.48 |
| [600qps](./2026-10-30-feed-600qps.md)   | 8  | 600  | 599.9  | 100% | 2.49 ms | 4.02 ms | **7.61 ms** | 27.76 ms | 5.26 |
| [1200qps](./2026-10-30-feed-1200qps.md) | 16 | 1200 | 1199.8 | 100% | 3.22 ms | 4.80 ms | 8.01 ms  | 27.53 ms | 6.06 |
| [2000qps](./2026-10-30-feed-2000qps.md) | 24 | 2000 | 1999.5 | 100% | 3.60 ms | 5.84 ms | 8.47 ms  | 37.03 ms | **8.66** |

累计 30,000+ 请求，**零失败、零业务错误**。

### 1.3 读法

- **延迟最低在 600 QPS 档（p99 7.61ms）**，不是 100 QPS 档——第一档的 p50 反而最高（4.69ms）。
  冷启动尾巴：4 个线程的并发压力不足以掩盖 JIT/连接池/缓存预热残差。这是正常现象，不是性能问题。
- **1200 → 2000 QPS，p99 只从 8.01ms 涨到 8.47ms（+5.7%）**，而 QPS 涨了 67%。
  线性扩展还远未到头，说明主链路（时间线读 + 多路召回 + 精排 + 缓存）没有暴露 CPU 或锁瓶颈。
- **只有 max 有明显抖动**（27~39ms），属于 GC 单次停顿与宿主调度噪声，不属于系统性尾延迟。
- **停在第 5 档是主动的**：压完 load 已到 8.66（10 核 86%），继续加压压的是压测机自己，
  数据会失真。按「不要跑崩」的约定收手。

### 1.4 诚实边界（别把这张表当容量承诺）

1. **单机同进程竞争**：压测器、gateway、feed-engine、Redis **全在同一台 Mac 上**，
   没有网络跳数、没有跨机序列化开销。这组数字是这套代码在零网络成本下的**处理能力上界参考**，
   不是生产容量。生产容量需要另起压测机 + 多实例 + 真实网络链路。
2. **未开 behavior-log**：本轮 `turbofeed.feed.behavior-log.enabled=false`，
   落盘路径的开销（JSON 序列化 + 异步队列 + 磁盘 IO）**没有计入**这些数字。
3. **Redis 是单节点 256MB 限内存**，不是生产的三主三从集群；跨槽路由、MOVED 重定向的成本未体现。
4. **只压了 `feed` 一个场景**。`playable` / `comment-list` / `like` / `comment-publish`
   四个场景的写路径（分片路由 + 计数 + 审核信号）未压。

### 1.5 复现方式

```bash
# 1) 起被测栈（Redis 7599 + gateway 8080 + engine 8083），见 docs/ops/deployment.md
# 2) 补时间线（否则 feed/recommended 返回空 items，压的是空转接口）
curl -X POST 'http://127.0.0.1:8080/api/v1/admin/feed/timeline/backfill?maxPosts=200&batchSize=50' \
     -H "Authorization: Bearer <ADMIN_TOKEN>"

# 3) 压测（每档重跑一次，改 --concurrency / --target-rps）
java -cp "tf-benchmark/target/classes:<deps>" com.turbofeed.benchmark.BenchmarkMain \
  --base-url http://127.0.0.1:8080 --scenario feed \
  --concurrency 24 --duration 15s --warmup 3s --target-rps 2000

# 4) 归档：把 target/benchmark/benchmark-feed-<ts>.md 复制到本目录并补进上表
```

**压测前必查**：`docker ps` —— 启动 Docker Desktop 会连带拉起既有的 nacos / redis-7000~7005
等容器（10 核 / 8GB 的 VM），宿主 load 会从 2.2 涨到 5.0，**污染基线**。

### 1.6 环境快照

| 项 | 值 |
| --- | --- |
| 压测机 | Mac OS X aarch64 / 10 核 / 16 GB / JDK 17.0.19 |
| 被测服务 | tf-gateway 8080（`-Xmx384m`）、tf-feed-engine 8083（`-Xmx384m`） |
| Redis | 单节点 7599，容器 `--maxmemory 256mb` |
| 数据库 | MySQL 127.0.0.1:3306（原生进程） |
| 压测器 | 闭合环路，`warmup=3s` 样本丢弃，分位口径 nearest-rank |
| 成功判定 | `2xx && businessCode == 0`（本项目鉴权失败也是 HTTP 200 + `code=40101`，只看状态码会造假 QPS） |

---

## 待补（未做）

- `playable` / `comment-list` / `like` / `comment-publish` 四个场景的基线
- 打开 behavior-log 后的同条件对照（量化落盘开销）
- Redis 集群模式下的同条件对照
- 跨机压测（压测器与业务分离）——这是拿到**可用于容量规划**数字的前提
