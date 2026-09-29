# 0056 修复推荐流缓存跨用户串号（个性化结果不得进共享缓存）

- 日期：2026-09-28
- 影响模块：`tf-feed-engine`（`RecommendedFeedService`）
- 类型：**缺陷修复（P0 正确性）**，无新功能、无配置变更、无 DDL

---

## 一、问题

`RecommendedFeedService#recommended` 的缓存 key 为 `tf:feed:rec:{page}:{limit}`，
**不含用户身份**；但写入缓存的是 `feedTimelineStore.readPage(userId, ...)` 的**个性化**结果。

在 15s TTL（{@code REC_CACHE_TTL}）内造成三类后果：

1. **跨用户串号**：用户 B 读到用户 A 的排序——包括 A 的兴趣加权与 A 的「不感兴趣」打压；
2. **匿名读到个性化页**：匿名访问（userId=null）可能命中某个登录用户写入的结果；
3. **发布后不立刻可见**：`invalidate()` 只删「前 5 页 × 2 种页大小」这 10 个确定 key，
   个性化结果本就不该进这些 key，登录用户反而要等满 15s TTL。

### 取证

`git show ee1e2ae`（`feat(feed): 媒体时长/完播指标 + caption #话题兴趣个性化推荐`）：

```java
-    public List<FeedItemView> recommended(int page, int size) {
+    public List<FeedItemView> recommended(int page, int size, String userId) {
         int limit = size <= 0 ? 20 : size;
         String key = REC_KEY_PREFIX + page + ":" + limit;   // ← 缓存 key 未跟着改
-        List<FeedItemView> fresh = feedTimelineStore.readPage(page, limit);
+        List<FeedItemView> fresh = feedTimelineStore.readPage(userId, page, limit);
```

引入个性化时改了读路径入参、漏改缓存 key。**这是缓存作用域问题，不是打分逻辑问题**——
因此 0052/0053/0054/0055 做的负反馈、热点召回、多样性重排、打分平滑，
在真实链路里被这层缓存整体打折甚至错乱。

---

## 二、修复

**只缓存匿名（{@code userId == null}）结果；已登录用户一律实时读。**

```java
boolean cacheable = userId == null;
String key = REC_KEY_PREFIX + page + ":" + limit;
if (cacheable) { /* 读缓存 */ }
List<FeedItemView> fresh = feedTimelineStore.readPage(userId, page, limit);
if (cacheable) { /* 写缓存 */ }
```

`invalidate()` 语义不变（仍只删 10 个匿名 key），且**足够**——
登录用户不缓存，发布后下一次读取必然是实时读，天然立刻可见。

### 为什么不给个性化结果也加一层「用户维度」缓存

| 方案 | 问题 |
|---|---|
| key 带 userId | 用户数无界 → `invalidate()` 删不干净 → 退化为等 TTL →「发布后立刻可见」这条硬需求丢失 |
| 按用户枚举 key 删除 | 等价于对 keyspace 做 SCAN，踩 0043 已明确规避的红线 |
| 全局版本号（key 带 gen，失效=INCR gen） | **可行**，但当前没必要：同一用户 15s 内重复请求同页的概率极低，命中率近零 |
| **只缓存匿名（本次采用）** | 零泄漏、失效语义不变、改动最小；个性化读本就是设计内的读路径 |

若将来量级确实需要个性化缓存，正确解法是**全局版本号**而非按用户枚举 key（已在 javadoc 写明）。

---

## 三、验证

真 Redis 集群（192.168.0.6:7000-7002）+ 真实 `RecommendedFeedService` / `FeedTimelineStore`：
`target/verify/RecCacheVerify.java`（`run_rec_cache.sh`）。

场景：同一流量池两条内容（cooking 更旧 / java 更新），userA 对 java 点「不感兴趣」。

| # | 断言 | 修复前 | 修复后 |
|---|---|---|---|
| ③ | userA：cooking 排在 java 之前（负反馈生效） | PASS | PASS |
| ④ | userA 请求后匿名缓存 key **未**生成 | **FAIL** | PASS |
| ⑤ | userB（无负反馈）：java 仍在 cooking 之前 | **FAIL**（串号） | PASS |
| ⑤b | 两用户顺序确实不同 | **FAIL** | PASS |
| ⑥ | userB 请求后匿名缓存 key **未**生成 | **FAIL** | PASS |
| ⑦/⑦b | 匿名请求写入缓存且带 TTL | PASS | PASS |
| ⑧ | 匿名二次请求命中缓存且内容一致 | PASS | PASS |
| ⑨ | `invalidate()` 删除匿名 key | PASS | PASS |
| ⑩ | 清理完成（可重复执行） | PASS | PASS |

- 修复前 **PASS=8 FAIL=4**；修复后 **PASS=12 FAIL=0**。
- 对照脚本：`run_rec_cache_old.sh`（从 `git show HEAD:` 导出旧类、置于 classpath 最前覆盖新类），
  用于证明「bug 真实存在」而非「我以为修好了」。

---

## 四、回归与影响面

- 无配置新增、无 DDL、无跨服务契约变更，`tf-gateway` 与前端不受影响。
- 匿名流量缓存命中率不变；登录用户由「可能命中共享缓存」变为**实时读**，
  单次请求多一次 ZSET 分页 + 若干 HASH/SET 读，属设计内成本。
- 顺带消除的隐患：登录用户此前依赖 `invalidate()` 清缓存才能看到新内容，
  而该 key 逻辑上就不该被他们复用，修复后不再有这条不一致路径。
