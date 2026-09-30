# 0063 精排接入短期兴趣画像（抖音式"当下在追什么"排最前）

> 关联：0061 时间衰减 / 0062 长短期双层画像。本变更把 0062 的短期层从"只影响召回"推进到"也影响排序"。

## 背景与动机
- 0062 让短期兴趣只参与**召回选标签**（`recallTags`）。但召回只决定"用户看不看得见这类内容"，
  真正决定"排多前"的是精排（`RankingModel`）。
- 整改前 `FeedTimelineStore#scoreOf` 的 `interestMatch` 只取**长期层** `weightedTags`，
  于是"刚刷了一堆钓鱼"的用户，相关钓鱼内容虽然能被召回，但排序分仍只按长期偏好算，
  短期突发兴趣无法把内容顶到前面——这正是抖音差距里"推荐不够跟手"的根因。

## 改动点
1. **`RankingFeatures`** 新增特征 `shortTermMatch`（短期兴趣匹配分，与 `interestMatch` 同源标签、各自独立累加）。
2. **`RankingProperties`** 新增 `shortTermWeight`（默认 1.0）与 `shortTermScoreCap`（默认 3.0），均可经 `turbofeed.feed.rank.*` 配置。
3. **`LinearWeightedRankingModel#score`**：`interest = 长期封顶 + shortTermWeight × 短期封顶`，
   两层各自先封顶（防单标签刷屏），再叠加。短期层关闭 → `shortTermMatch` 恒为 0 → 退化为纯长期（与改造前语义一致）。
4. **`FeedTimelineStore#readPage` / `scoreOf`**：一次性取出 `shortInterest = shortTermTags(userId, INTEREST_TOP_N)`，
   在组装特征时按同源标签累加 `shortTermScore`，传入 `RankingFeatures`。

## 设计取舍
- **为什么长期 / 短期各自独立成特征，而不是把 `recallTags` 融合值直接当兴趣分**：
  召回归一化到 `[0,1]`、量纲不真实，不适合直接在精排里加权；排序侧继续用各层的**原始衰减权重**
  （可解释、量纲真实），两套分数各司其职（见 0062 注释）。
- **默认偏保守**：`shortTermWeight=1.0`、`shortTermScoreCap=3.0` 与长期同量级——短期爆发可"追平甚至超过"
  弱长期偏好，但不会轻易压过强长期偏好。要更跟手就调大 `shortTermWeight`（产品/算法按效果调参，
  不写死在代码里）。

## 验证
- 编译 `tf-feed-engine`（`-am` 先建 `tf-shared`，否则 m2 旧 jar 缺 `FeedItemView.tags`）。
- **真实启动引擎**：`Started FeedEngineApplication in 6.997s`、ERROR=0（绕不过容器的单元验证不能替代真启动）。
- 单元验证 `InterestShortTermRankingVerify`（4/4）：
  ① 同内容短期爆发抬高排序分；② `shortTermWeight=0` 退化为纯长期；
  ③ 纯短期爆发可超过纯弱长期偏好；④ 负向命中沉底不变量不被短期项破坏。

## 待续
- 仍未做：session 级序列建模（最近点击序列做 target-attention）。
- `shortTermWeight` 的最终取值建议结合线上 CTR 实验定，本默认值仅为"能生效且不激进"。
