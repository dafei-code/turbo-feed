# 0073 · CI 转绿：compose 插值语法 + #307 残留测试与缓存兼容

- **状态**：已完成
- **优先级**：P0（CI 红阻塞合入；两处均为已合入改动的回归）
- **关联**：0072（#310 拆分）、0070（完整归因打通 #307）、#311（Redis HA 部署接线）

## 为什么做

CI run `40886684505`（commit `58cb982`）两个 job 均红，均为**既有改动的回归**，与 #310 拆分本身无关：

| job | 失败步骤 | 根因 |
| --- | --- | --- |
| `compose-smoke` | 冒烟——默认 profile 配置可解析 | `docker-compose.yml` 用了 `${VAR:single}`，Compose 默认值语法应为 `${VAR:-single}` → `invalid interpolation format` |
| `build` | Build & verify | #307 把 `readPage` / `recommended` 返回类型改为 `List<RecommendedItem>`，两个引擎测试未同步；且 `RecommendationPipeline` 新增了 `ExperimentProperties` 构造参数 |

## 修复

### 1. compose 默认值语法（#311 引入）

`${TURBOFEED_REDIS_MODE:single}` → `${TURBOFEED_REDIS_MODE:-single}`（gateway / feed-engine 各一处）。

Compose 变量语法只有 `${VAR}`、`${VAR:-default}`、`${VAR-default}`、`${VAR:?err}` 等；`${VAR:single}`
不是合法形式，`docker compose config` 在**解析阶段**即失败——所以反证步骤（缺凭据必须失败）能过，
而正常配置反而过不了，是典型的「门禁只测了失败分支」。

### 2. #307 归因改造的残留测试

| 文件 | 改动 |
| --- | --- |
| `FeedTimelineStoreReadPagePreRankTest` | `readPage` 返回值改 `List<RecommendedItem>`（两处局部变量）；`keysOf` 签名改 `List<RecommendedItem>` 并取 `item().timelineKey()`；`RecommendationPipeline` 构造补 `new ExperimentProperties()`（实验默认关，守护默认配比） |
| `RecommendationPipelineRecallTest` | 仅 `keysOf` 签名改 `List<RecommendedItem>`（该文件构造已带 `ExperimentProperties`） |

两处 `keysOf` 助手此前以 `List<FeedItemView>` 接收 `readPage` / `recommend` 结果，类型变更后编译失败。

### 3. 推荐流缓存向后兼容（真实行为缺口，非仅测试）

`ItemViewCodec.parseListRecommended` 原实现按 `List<RecommendedItem>` 直接反序列化。但缓存在 #307 前
写入的是**裸 `FeedItemView` 数组**，这些 Key 的 TTL 未到，滚动发布期间会被读到——解析后 `item()` 为
`null`，被 `filterConsumable` 整体剔除，**整页推荐流变空**（用户侧表现为「刷不出内容」）。

改为逐节点判别：

- 含 `item` 字段 → 新格式，按 `RecommendedItem` 解析（保留 `recallChannel` / `poolLevel`）；
- 否则 → 旧格式，按 `FeedItemView` 解析并包装为 `new RecommendedItem(view, null, null)`（无归因降级）。

有归因信息时保留，没有时降级而不丢内容——与「读路径宁可少归因，不可少内容」的口径一致。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `docker-compose.yml` | 两处 `${TURBOFEED_REDIS_MODE:single}` → `:-single` |
| `tf-feed-engine/.../timeline/FeedTimelineStoreReadPagePreRankTest.java` | 返回类型 + `keysOf` + 补构造参数 |
| `tf-feed-engine/.../timeline/RecommendationPipelineRecallTest.java` | `keysOf` 签名 |
| `tf-feed-engine/.../contract/ItemViewCodec.java` | `parseListRecommended` 兼容旧缓存形状 |
| `docs/changelog/0073-CI转绿-compose插值与归因测试兼容.md` | 本文件 |

## 验证证据

- `mvn -o clean test-compile` 全仓 → BUILD SUCCESS（8 模块）。
- `mvn -o test` 全仓 → BUILD SUCCESS：`tf-gateway` 261 用例（8 skip，需 Docker 的集成测）、
  `tf-feed-engine` / `tf-counter` / `tf-shared` 全绿。
- 本地 `docker compose config --quiet` 与 `docker compose --profile mq config --quiet` → 均 exit 0。

## 风险与未决

- 旧缓存兼容分支只在滚动发布窗口内命中（TTL ≤15s），之后自然消失，无长期维护成本。
- 该分支不引入新依赖、不改写路径；写路径仍统一产出新格式。
