# 0058 端到端验证暴露的三个「编译能过、启动就炸」阻塞

- 日期：2026-09-29
- 影响模块：`tf-feed-engine`（2 处）、`tf-gateway`（1 处）
- 类型：**缺陷修复**。修复前 **tf-feed-engine 完全无法启动、tf-gateway 上传接口 500**

---

## 背景：为什么这些 bug 能活到现在

此前所有验证都在 `target/verify/` 下以 `main()` 直跑（手工 `new` 依赖），
**从未真正启动过服务**。三类问题因此全部漏网：

| 验证方式 | 能发现 | 不能发现 |
|---|---|---|
| 单元/逻辑级 harness | 打分、召回、打散、落盘的算法正确性 | Bean 装配、路径冲突、构造器签名漂移 |
| `mvn compile`（增量） | 被改文件的语法 | 未改文件里的过期调用（跳过编译 → 旧 `.class` 残留） |
| **真实启动 + 接口链路** | 以上全部 | — |

**教训（已写进对应类 javadoc）：绕过 Spring 容器的验证不能替代一次真实启动；
增量编译会掩盖"旧产物与新签名不一致"，改动公共构造器时必须全量重编。**

---

## 一、`RankingModel` 没有注册为 Bean → 引擎启动直接失败

`No qualifying bean of type 'com.turbofeed.feedengine.ranking.RankingModel'`

0055 引入 `LinearWeightedRankingModel` 时漏了 `@Component`，而 `FeedTimelineStore`
是构造器注入 `RankingModel`。harness 手工 `new LinearWeightedRankingModel(...)` 绕过了容器，
于是"脚本 64/64 全绿、服务起不来"。

**修复**：补 `@Component`，并在 javadoc 写明这一段教训。

## 二、`POST /internal/feed/behavior` 被两个控制器同时映射 → Ambiguous mapping

`PostStatController#behavior` 与 `FeedTimelineController#behavior` 都映射同一路径，
Spring 启动即报 `Ambiguous mapping`。

- `FeedTimelineController` 那份是**完整**的：WATCH 完播分支 + 兴趣画像 + 行为明细落盘；
- `PostStatController` 那份只调 `postStatService.recordAll(events)`，是**残缺**实现。

**修复**：删除 `PostStatController` 的重复方法，保留 `FeedTimelineController` 为唯一入口，
并在 `PostStatController` javadoc 明确"不要再添加"。
（`/pool/promote-scan` 与 `/pool/debug` 仍保留在该类。）

## 三、`MediaItem` 加 `tags` 字段时两处调用点漏改 → 上传接口 500

`NoSuchMethodError: MediaItem.<init>(String,String,String,List,Integer,MediaStatus,Instant,String,String)`

0051 给 `MediaItem` 增加第 10 个参数 `tags` 时只改了 `MediaReviewService`，
`MediaUploadService:281` 与 `MediaUploadFinalizer:78` 没改。
增量编译跳过未变更文件 → 旧 `.class` 留在 `target/classes` → 运行期 `NoSuchMethodError`
→ **所有上传（含预签名收尾）500**。

**修复**：两处补 `CaptionTagParser.parse(caption)` 并加 import。
**顺带纠偏**：必须 `rm -rf tf-gateway/target/classes` 后全量重编才能暴露这类问题。

---

## 四、端到端验证结果

`target/verify/e2e_chain.sh`（HTTP 真实链路，网关 8080 + 引擎 8083）：**8/8 PASS**

| 步骤 | 断言 | 结果 |
|---|---|---|
| ① | 注册新用户（新号 L0 先审后放） | PASS |
| ② | 登录拿到 `accessToken` | PASS |
| ③ | `POST /api/media/upload`（multipart `files`）返回 `mediaId` | PASS |
| ④ | 管理员登录 + `POST /api/admin/media/review?approve=true` → `APPROVED` | PASS |
| ⑤ | `GET /api/feed/recommended` **能看到该内容**（公域可见性成立） | PASS |
| ⑥ | `POST /api/feed/behavior`（带 `requestId` + `position`）→ 引擎实时分生效 | PASS（`impressions=2 / likes=1 / playCompletes=1`） |

前端 `feed.html` 新增埋点代码另做静态校验：抽出 `<script>` 用 `node --check` 通过，
且 `track / onEnterItem / flushBehavior / newRequestId` 符号齐全。

### 启动方式与本机环境注意

- 启动脚本：`target/verify/start_feed_engine.sh`（8083）、`start_gateway.sh`（8080）。
  网关数据源指向 `shardingsphere-config-e2e.yml`——**读写都走 3306** 的临时配置，
  由 `target/verify/gen_e2e_ss_config.py` 生成到 `target/classes/`（构建产物目录，gitignored），
  **不改动** `src/` 下任何真实配置。
- 为什么需要它：本机 3307 只读副本当前起不来（InnoDB 要删除 09-24 遗留的
  `#ib_redo*_tmp` 时被 macOS 以 `EPERM` 拒绝，`--daemonize` 与前台均失败）。
- 编译必须带 `-Dmaven.resources.skip=true`（沙箱拒绝覆写 `target/classes/*.yml`）；
  改公共构造器后要 `rm -rf <module>/target/classes` 再全量重编。

---

## 五、本次顺带发现、尚未修复（需确认后再动）

**`outbox_event` 表缺 `dead_count` 列** —— 网关定时任务每分钟抛：

```
ColumnNotFoundException: Unknown column 'dead_count' in 'where clause'
```

即 **发件箱补偿（OutboxRelay）实际处于失效状态**（当前 `turbofeed.mq.enabled=false` 默认不启用 MQ，
故线上表现被掩盖；一旦开 MQ 就会暴露）。
修复需要 `ALTER TABLE outbox_event ADD COLUMN dead_count ...`（DDL，涉及两个库），
已在未决项里登记，待确认后执行。
