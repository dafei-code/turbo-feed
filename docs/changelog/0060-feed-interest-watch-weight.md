# 0060 兴趣画像完播加权 —— WATCH 从"固定 +0.2"改成"按观看进度三段"

- 日期：2026-09-29
- 影响模块：`tf-feed-engine`（`InterestService`）
- 类型：行为修正；新增配置 `turbofeed.feed.interest.*`（4 个权重/阈值，均有默认值）

---

## 一、为什么要做这个（对齐抖音的"完播率是核心正样本"）

抖音把**完播率**当作最强的正样本信号之一：播放进度是**连续信号**，
划走 5% 和看完 100% 表达的偏好强度完全不同。抖音甚至会区分
"有效播放"（超过一定秒数/比例）才计入，就是为了别让"手指一滑"污染画像。

改造前我们的问题有两条，都是**实证**得来的（不是推测）：

### 问题 1：`WATCH` 一律 +0.2，**不看观看时长**

```java
private static final Map<String, Double> WEIGHTS = Map.of(
        "LIKE", 1.0, "COMMENT", 1.5, "SHARE", 1.5,
        "WATCH", 0.2,          // ← 划走和看完，一个价
        "DISLIKE", -1.5);
```

`FeedBehaviorEvent` 里明明带着 `watchDuration` / `mediaDuration`，但画像累加时**压根没读**。
后果：划走是最常见的行为（用户刷得最快），它和"看完"贡献相同权重，
画像因此被大量**划走噪声主导**，真正看完的强信号被稀释。

更讽刺的是：完播率在 `PostStatService` 里是算了的（用于热点榜），
但**完全没喂给画像**——同一个信号，两处口径不一致。

### 问题 2：`PLAY_COMPLETE` 根本不在权重表里

权重表只有 5 个 key，**没有 `PLAY_COMPLETE`**。
而旧端（以及 `turbo-feed-ui` 之外的一批客户端）是用这个**二进制完播事件**上报的。
`WEIGHTS.getOrDefault("PLAY_COMPLETE", 0.0)` → **0.0** → 直接 `return`。
也就是说：**老端的完播行为从来没进过画像**。

这是典型的"枚举漏项"：新增行为类型时，只改了统计侧（PostStatService），
没同步画像侧（InterestService），且**没有任何测试会发现它**——因为 0 权重不会报错，只是静默不生效。

## 二、实现

### 分段权重（阈值与 `PostStatService` 同口径）

| 观看进度 `ratio = watchDuration / mediaDuration` | 权重 | 语义 |
|---|---|---|
| `< 0.3` | **0.0** | 划走——不表达兴趣，**一分不加** |
| `0.3 ≤ ratio < 0.7` | 0.1 | 看一半：弱信号 |
| `≥ 0.7` | 0.3 | 看完：强信号（0.7 与 `PostStatService#PLAY_COMPLETE_RATIO` 一致） |
| 缺时长字段（`null` / `mediaDuration<=0`） | 0.1 | 保守按"看一半"：**宁可低估也不把未知当看完** |
| `PLAY_COMPLETE`（二进制事件） | 0.3 | 发即代表看完，等同看完档 |

```java
private double weightOf(String type, FeedBehaviorEvent event) {
    if ("WATCH".equals(type)) { return watchWeight(event); }
    if ("PLAY_COMPLETE".equals(type)) { return playCompleteWeight; }
    return WEIGHTS.getOrDefault(type, 0.0);
}
```

### 为什么不把"划走"做成负权重

划走是**最弱的信息**，不是负反馈。用户在推荐流里本来就会快速划过大量内容，
给负权重等于让"没看完"惩罚内容，画像会被划走次数多的热门标签反向压制。
**负向信号只由显式表达（DISLIKE / 不感兴趣）提供**——这个边界不能模糊。
负权重留给 0061 的负反馈分层，不在这里引入。

### 为什么 `WATCH` 从权重表里删掉了

`weightOf` 已经接管 `WATCH`，表里那条 `"WATCH", 0.2` 变成**不可达的死配置**。
留着它，下一个人改权重表时会以为改它能调整 WATCH，实际毫无作用——这种陷阱必须清掉。

### 配置项（均有默认值，不改配置行为不变）

```yaml
turbofeed.feed.interest:
  watch-skip-ratio: 0.3        # 低于该观看比例 = 划走，不计画像
  watch-partial-weight: 0.1    # 看一半
  watch-complete-weight: 0.3   # 看完
  play-complete-weight: 0.3    # 旧端二进制完播事件
```

`@Value` 字段同时给了 **Java 默认值**：`target/verify` 的 harness 是 `new InterestService(...)` 手搓的，
不走 Spring，纯 `@Value` 字段会是 `0.0`，测试会假失败（这次实测踩到）。

## 三、验证

`target/verify/InterestWeightVerify.java`（真 Redis 集群 + 真实 `InterestService`）**10/10 PASS**：

| # | 断言 | 结果 |
|---|---|---|
| ① | 看完（ratio=1.0）→ 权重 0.3 | PASS |
| ② | 看一半（ratio=0.5）→ 权重 0.1 | PASS |
| ③ | 划走（ratio=0.1）→ 权重 **0.0**（改造前是 +0.2） | PASS |
| ④ | 缺时长字段 → 保守取 0.1 | PASS |
| ⑤ | `PLAY_COMPLETE` → 0.3（改造前是 **0**，完全不进画像） | PASS |
| ⑥ | 看完 vs 划走：前者权重更高（信号可比） | PASS |
| ⑦ | `LIKE` / `DISLIKE` 等非 WATCH 行为权重不变（无回归） | PASS |
| ⑧ | 划走后画像 Hash 中该标签**不出现** | PASS |
| ⑨ | 看完后画像 Hash 中该标签权重 = 0.3 | PASS |
| ⑩ | 阈值/权重可通过配置覆盖 | PASS |

全套回归 `run_all_feed.sh`：**83 PASS / 0 FAIL**。

## 四、踩坑记录

- **`target/verify` 的 harness 不走 Spring 容器**：`@Value` 字段必须同时给 Java 默认值，
  否则手搓实例里全是 `0.0`，测出来的"权重为 0"会被误读成"逻辑没生效"。
- **回归基线与跨用例污染**：本次合入前后，`run_all_feed.sh` 一度报 5 个 FAIL，
  其中 4 个是**跨用例污染**——`e2e_chain.sh` 会往真实流量池投递一条审核通过的内容，
  遗留下来被 HotRecall / Diversity / Ranking 的"清理后推荐流为空"类断言读到。
  已新增 `target/verify/reset_feed_redis.sh`（清 `tf:*`），套件开始前统一归零；
  `e2e_chain.sh` 自身也在出结果后调用它自清理，并删掉生成的测试图片。
  **教训：每个脚本只清理"自己造的键"，等于把"别人留了什么"变成隐式契约，必然漂移。**
- **偶发失败不能靠"再跑一次"翻篇，但也别急着改断言**：`BehaviorLogVerify` 的
  「①d 落盘行数 = 4」在整套连跑中失败 2 次、单跑 15 次均不复现。
  先加排障埋点（打印 `rawLines` / `rows`）并把 `[debug]` 行纳入套件输出，
  再连跑 4 次复现出 **rawLines = 8 / 12 / 16 / 20（每次 +4）**，
  于是真相只有一个：**上一次运行的文件没被删掉，落盘是 APPEND，行数在累加**。
- **真正的 bug 在验证脚本，不在产品代码**：清理逻辑写的是
  `catch (Exception ignore) {}`——**把"目录删不掉"静默吞了**。
  本次环境里删除被沙箱拦截，于是每次运行都在旧文件上追加 4 行，
  表现为"落盘行数不对"这种**完全指向错误方向**的假失败。
  修复两条：① 清理失败改为**打印告警**（不再 ignore）；② 每次运行落到
  `behavior-log-test/run-<nanoTime>/` 独立子目录，**结构上不可能跨运行累加**。
  **纪律：验证脚本里的 `catch ignore` 是缺陷，不是防御。**
