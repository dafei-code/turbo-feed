# 0074 · tf-hotspot 本地缓存：超限整体清空 → 热/冷两段近 LRU 淘汰

- **状态**：已完成
- **优先级**：P2（评审文档 `project-review-2026-10.md` §4「`tf-hotspot` 半残——热/冷桶迁移未做」）
- **关联**：ADR-0004（tf-hotspot 定位为进程内 SDK）、#310（模块割裂校准，`service-split.md` §3）

## 为什么做

`LocalCacheWithBroadcast` 是 tf-hotspot 唯一的实际能力，已被两个真实消费者复用：

| 消费者 | 缓存内容 | 容量 |
| --- | --- | --- |
| `tf-gateway` `AccountStateCache.levelCache` / `writeCache` | 每个用户的账号状态 | `turbofeed.state-cache.max-entries`（默认 100000） |
| `tf-feed-engine` `HotTimelineKeyCache` | 全局热点榜 Top-CAP | 1000（单键，实际恒 1 条） |

原淘汰实现只有一个动作：

```java
if (map.size() > maxEntries) {
    map.clear();          // 超限 → 全清
}
```

问题不在「有界」，而在**清空粒度**：只要出现一次批量冷 key 写入（典型如运营侧按用户 ID 扫一遍、
或引擎侧一次榜单/索引全量刷），条目数就会瞬间越过上限，`clear()` 把**正在被高频命中的热条目一并冲掉**，
命中率在那一刻归零，随后是一波集中回源打 Redis。`AccountStateCache` 是写路径上的账号状态校验缓存
（封禁/禁言判定），回源风暴会直接放大到鉴权链路。

这正是评审里「tf-hotspot 半残：热/冷桶迁移未做」指的那处。

## 设计：两段式（冷/热）+ 同段近 LRU

### 条目结构

`Holder` record 升级为 `Entry<V>`：

| 字段 | 用途 |
| --- | --- |
| `value` / `expireAt` | 原 TTL 语义，不变 |
| `loadedAt` | 跨实例失效 version stamp 判定（原有语义，不变） |
| `lastAccess`（`volatile`） | 最近一次**命中**时刻，同段内 LRU 依据 |
| `hot`（`volatile`） | 是否进入热段 |

### 冷 → 热提升

`getOrLoad` 命中分支新增一次 `hit.touch(now)`：`lastAccess = now`，且 `hot = true`。
语义是「**二次访问即热**」——首次加载落冷段，再被读到一次就进热段。

不引入独立的分段队列（LRU-K / 双链），只用两个字段表达分段，是为了保持 SDK 零依赖、
零锁退化：`ConcurrentHashMap` + 两个 volatile，命中路径仍然无锁。

### 淘汰规则（`evictToBound`）

触发条件：**仅在新增键**时（`map.put` 返回 `prev == null`）检查。覆盖同 key 不增长条目数，无需淘汰。

1. 未超上限 → 直接返回（命中路径零额外开销）。
2. 计算本次淘汰量：`target = min(size - maxEntries + margin, size - 1)`，其中 `margin = max(1, maxEntries/20)`。
   - **5% 批量余量**：`ConcurrentHashMap` 上找「最久未访问」必须全表扫描。若每次写入都扫一遍，
     缓存满之后的写入会退化成 O(n)。按余量一次淘汰多条，把扫描摊薄到每 ~5% 容量一次。
   - `size - 1` 兜底：至少给**刚写入的那个键**留一个位置，保证写入方立刻可读到自己的值。
3. 收集候选（**排除受保护键**），排序：
   ```java
   victims.sort(Comparator
           .comparing((Map.Entry<K, Entry<V>> e) -> e.getValue().isHot())   // 冷(false) 在前
           .thenComparingLong(e -> e.getValue().lastAccess()));            // 同段内最久未访问在前
   ```
4. 按序删除，用 `map.remove(k, v)` 的**两参条件删除**：并发下条目可能已被其它线程替换，
   条件删除避免误删新值。

净效果：冷段被清空之前，热段一条不动；只有热段已经占满上限时才会开始淘汰热段里最久未访问的。

### 一个刻意的取舍

`hot` 一旦置位不再回落。理论上可以做「热段降级」（长时间未访问退回冷段），但那需要在
`touch` 之外再引入时间衰减判定，而本组件的淘汰是**按需全表扫描**、没有后台线程——
降级只能在扫描时顺带算，收益有限（热段本身也按 `lastAccess` 排序，最旧的仍会被优先淘汰），
却要多维护一份时间窗口状态。因此当前**不做降级**，靠 `lastAccess` 排序兜住「热但已冷」的条目；
若日后有证据表明热段被长期占满（如 `AccountStateCache` 的 `removed` 日志显示持续淘汰热条目），
再补降级逻辑。该判断已写进类注释。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `tf-hotspot/.../cache/LocalCacheWithBroadcast.java` | `map` 类型改 `Entry<V>`；新增 `evictToBound`；`Holder` → `Entry`（+`lastAccess`/`hot`/`touch`）；命中路径 `touch`；新增包可见 `isHot`；类注释「边界」段落重写；新增 `ArrayList`/`Comparator`/`List`/`Objects` import |
| `tf-hotspot/src/test/.../LocalCacheWithBroadcastTest.java` | 删除锁定旧行为的 `maxEntriesTriggersWholeClear`；新增 3 例（见下） |
| `docs/changelog/0074-hotspot热冷桶淘汰.md` | 本文件 |

**未改动**：构造器签名、`getOrLoad` / `invalidate` / `invalidateLocal` / `onInvalidateMessage` /
`sweepExpired` / `size` 的对外语义、`CacheInvalidationPublisher` 契约、version stamp 失效逻辑。
两个消费者无需任何改动。

## 验证证据

`tf-hotspot` 单测 10/10 BUILD SUCCESS，其中 3 个新用例直接对应本次缺陷：

| 用例 | 断言 | 旧实现下 |
| --- | --- | --- |
| `maxEntriesIsBoundedWithoutWholeClear` | 容量 2 写 3 个 key：`size() <= 2` 且刚写入的 key 必命中不重载 | **失败**——`clear()` 后重载 |
| `coldEntryPromotedToHotAfterSecondAccess` | 首读 `isHot=false`，二读 `isHot=true` | 无 `hot` 概念 |
| `hotEntriesSurviveColdBurst` | 1 条热 + 200 条冷洪泛 / 容量 50：热条目仍命中、`size() <= 50` | **失败**——热条目被冲掉 |

整仓 `mvn -o test` 回归见下节。

## 风险与未决

- **淘汰是近似 LRU**：`ConcurrentHashMap` 的迭代顺序不保证，同 `lastAccess` 的条目淘汰顺序不定。
  对本场景（缓存加速，非正确性载体）可接受。
- **扫描成本**：一次淘汰扫描 O(n) + 排序 O(k log k)，k 为候选数。按 5% 余量摊薄后，
  均摊到每次写入约 O(n/0.05n) = O(20) 次比较级别，可接受。`maxEntries` 极大（10 万）时单次扫描
  仍是 10 万次遍历，但仅在「缓存刚写满且新增键」时发生，约每 5000 次写入一次。
- **热段不降级**：见上「一个刻意的取舍」。
- 评审文档中 tf-hotspot 的其它缺口（如指标暴露 `hit/miss/evict` 计数）本次未做——本次只修
  有明确正确性影响的淘汰策略；指标属观测增强，留在 P2 观测项里统一处理。
