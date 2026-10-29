# 推荐系统引入机器学习技术实现方案（从 A0 轻量学习升级到真 ML）

> 状态：
>
> **设计稿（未实施）**
>
> —— 本文是引入真实机器学习能力的技术实现方案，落地时按仓库约定
> 同提交同步模块文档并追加编号 changelog。
> 前置阅读：
>
> `docs/architecture/recommendation-design.md`
>
> （出仓 / 画像蓝图）、
>
> `docs/architecture/user-favorite-design.md`
>
> 、
> 以及引擎 
>
> `recall/`
>
> 、
>
> `ranking/`
>
>  下现有类（本文大量复用其已留好的 "缝"）。



***

## 0. 结论先行



1. **不要另起炉灶**。仓库已经搭好了 ML 的骨架：标签共现 SGNS 向量训练器、四路向量召回通道、

   RediSearch KNN 向量索引、13 维 `RankingFeatures` 特征契约、`RankModelClient` 远程模型缝、

   确定性 DeepFM / MMoE 占位实现。引入 ML = **把这些占位换成 "用真实行为训练出来的产物"**，读路径代码零改动。

2. **现在缺的不是模型，是三样东西**：① 标注样本（`behavior-log` 默认关）；② 离线训练 job；

   ③ 模型 serving（`RankModelClient` 没有实现 bean）。先补数据，再换召回，再换精排，最后规模化。

3. **铁律：单向数据流 + 全链路 fail-open**。离线训练产物（向量 / 模型权重）经 Redis KV 与模型服务回灌在线；

   任何 ML 环节挂掉都自动退回现有规则召回 + 线性 / 确定性 FM—— 上线风险被结构性消解。

4. **分四期，每期可独立验收、可 A/B、可回滚**：P0 数据地基 → P1 召回换真 embedding → P2 精排接真模型 serving → P3 规模化。



***

## 1. 现状盘点：已有什么 vs 缺什么

### 1.1 已经落地的 "轻量学习 / ML 骨架"（A0，确定性、无训练产物）



| 环节      | 现有类                                                                | 现状                                                                                                                                                                            |
| ------- | ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 向量召回    | `VectorRecallChannel`（第四路）                                         | 用户向量 vs 候选向量余弦，fail-open 退回规则召回                                                                                                                                               |
| 向量索引    | `VectorIndexWriter`                                                | 写 `tf:vec:item:{key}` hash + RediSearch `FT.CREATE`（index `tfVecIdx`，dim=64）做 KNN；plain Redis 下 HSET 可写、暴力余弦回退                                                                |
| item 向量 | `TagItemEmbeddingSource` / `TagCooccurrenceTrainer`                | **A0**：tag-SGNS 共现训出 tag 向量，item 向量 = 其标签向量均值（L2 归一），写 `tf:emb:item:{key}`                                                                                                    |
| 用户向量    | `RedisUserEmbeddingSource` / `InterestUserEmbeddingSource`         | **A0**：由用户兴趣标签向量即时聚合，非行为训练                                                                                                                                                    |
| 特征契约    | `RankingFeatures`                                                  | 13 维稳定特征（recency/impressions/playCompletes/likes/comments/shares/dislikes/interestMatch/shortTermMatch/sessionMatch/negativeHit/realtimeMatch/authorHealthScale），原始计数 + 置信度平滑 |
| 精排占位    | `DeepFmRankingModel`（本地确定性 FM）、`MultiTargetRankingModel`（MMoE 式融合） | 确定性伪随机嵌入做特征交叉，**不依赖训练产物**；`deepfm-cross-weight=0` 时 ≡ 线性                                                                                                                      |
| 远程模型缝   | `RankModelClient`                                                  | **接口已定义、无实现 bean**；`remote-model-enabled=true` 即热切远程推理，异常 fail-open 回本地 FM                                                                                                    |
| 实时特征    | `RealtimeFeatureService` / `UserRealtimeProfile`                   | session 级最近互动 + 短期 burst 聚合成页面级实时画像                                                                                                                                           |
| 标注数据    | `interaction_event` 表（单表）、`BehaviorLogSink`（JSONL）                 | **存在但&#x20;**`behavior-log`**&#x20;默认关**，样本未沉淀                                                                                                                                |

### 1.2 缺口（引入真 ML 要补的）



| 缺口                | 影响                                         |
| ----------------- | ------------------------------------------ |
| 样本未沉淀             | 行为明细默认关，离线训练无标签可用                          |
| item/user 向量非行为训练 | 当前是 "标签共现 + 标签均值"，捕捉不到 "看过 A 的人也看 B" 的协同信号 |
| 无离线训练 job         | 没有把样本→向量 / 模型权重的流水线                        |
| 无模型 serving       | `RankModelClient` 无实现，精排仍跑确定性公式            |
| 无 A/B 度量          | 模型优劣无法证伪                                   |



***

## 2. 总体架构（单向数据流，在线零侵入）



```
离线/近线训练（新增，不碰 OLTP）
  interaction_event / BehaviorLogSink(JSONL)
        │  样本构造（曝光→互动 正/负样本，含 requestId/position/channel/pool）
        ▼
  训练 job（PyTorch / 现有 Java 侧可先跑 i2i）
        ├── 双塔 user/item 塔 → item 向量 ──► 写回 tf:emb:item:{key} / tf:vec:item:{key} + RediSearch KNN
        │                                 （复用 VectorIndexWriter / EmbeddingCodec，读路径零改）
        └── DeepFM/MMoE 精排权重 ─────────► 导出 ONNX/TorchScript → 模型服务（RankModelClient 实现）
                                                      │
在线（现有 tf-feed-engine，读路径不动）
  多路召回(热点/兴趣/流量池/向量) → 粗排 → 精排
        精排: RankingFeatures ──remote-model-enabled=true──► RankModelClient.predict(远程推理)
                └─ 异常/超时 → fail-open 退回本地确定性 FM（DeepFmRankingModel）
```

**三条铁律**：



* **离线产物经 Redis KV / 模型服务回灌在线，不回写业务库**；

* **任何 ML 环节失败 → 自动退回规则召回 + 本地 FM**，推荐页不挂；

* **特征在线 / 离线同源**（`RankingFeatures` 即请求体雏形），消除训练 - serving 偏差。



***

## 3. 分期落地

### P0：数据地基（零新组件，一切的前提）



| 动作                    | 说明                                                                                                                |
| --------------------- | ----------------------------------------------------------------------------------------------------------------- |
| 打开 `behavior-log`     | `turbofeed.feed.behavior-log.enabled=true`，让曝光 / 完播 / 点赞 / 收藏明细按天落 JSONL；补全前端埋点（含收藏按钮，见收藏方案）                      |
| 样本口径固化                | 正样本：曝光后发生 LIKE/COMMENT/SHARE/FAVORITE/ 完播≥阈值；负样本：曝光 requestId 内**未互动**的同请求条目（`requestId`/`position` 正是 0057 为此预留） |
| 落 `interaction_event` | 现有表已记录非曝光事件，补 channel/pool/requestId，作为双塔 / 精排训练的事实表                                                              |
| 先跑通离线取数               | 写一个离线脚本（可放 `tf-gateway/offline/` 旁或独立 job），把 N 天 JSONL/`interaction_event` 导出为训练样本（CSV/Parquet），校验样本量与正负比         |

**验收**：能导出 "某用户在某请求位看到哪些内容、是否互动" 的可训练样本；正负样本比例可查。

### P1：召回换真 embedding（双塔 /i2i，复用现有向量通道）

**目标**：把 "标签均值向量" 升级成 "从真实共现行为学出的协同向量"，召回第四路立刻有语义泛化能力。



| 决策   | 选型                                                               | 理由                                                                                              |
| ---- | ---------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| 召回模型 | **P1 先用 i2i /item2item（ALS 或物品协同），P2 再上双塔**                      | i2i 无需 GPU、离线 job 可快速起步；双塔等样本量与特征齐全后再上                                                          |
| 训练产物 | item embedding（dim 沿用 64）                                        | 与现有 `tf:emb:item:*` / RediSearch `tfVecIdx` 同维度，索引结构零改                                          |
| 写入   | **复用&#x20;**`VectorIndexWriter`**&#x20;/&#x20;**`EmbeddingCodec` | 训练 job 把向量写回同一批 Redis KV；`VectorRecallChannel` 与 `VectorUserSourceEnabledCondition` 读路径不变       |
| 用户向量 | 在线用 "用户最近 N 个互动 item 向量加权聚合"（替代纯标签聚合）                            | 新增一个 `BehaviorUserEmbeddingSource` 实现 `UserEmbeddingSource`，与 `InterestUserEmbeddingSource` 可开关 |
| 冷启动  | 新用户 / 新内容回退 tag 向量（现有 `TagItemEmbeddingSource`）                  | 无行为就用内容标签向量，fail-open                                                                           |

**改动点**：新增离线训练 job（产出 item 向量）+ 新增 `BehaviorUserEmbeddingSource`（实现现有接口），**召回通道与索引零改**。

**验收（A/B）**：向量召回路 vs 纯规则召回，个性化流的曝光→互动转化率是否提升；离线算 hitRate/NDCG。

### P2：精排接真模型 serving（实现 `RankModelClient`）

**目标**：把 `DeepFmRankingModel` 的本地确定性 FM 换成远程真模型，打分语义不变（越大越靠前）。



| 决策      | 选型                                                               | 理由                                                                |
| ------- | ---------------------------------------------------------------- | ----------------------------------------------------------------- |
| 模型      | **DeepFM 起步，再 MMoE 多目标（CTR / 完播 / 时长 / 互动）**                     | 仓库已有这两个占位实现，特征交叉与多目标语义已对齐，替换即可                                    |
| 特征      | **直接复用&#x20;**`RankingFeatures`**&#x20;13 维**                    | 它就是为模型设计的稳定契约；在线由 `RealtimeFeatureService` + `PostStatService` 填充 |
| 特征工程    | 原始计数进模型（保留 impressions 样本量），比率由模型 / 平滑算                          | 与 `RankingFeatures` 类注释的取舍一致（小样本置信度）                              |
| serving | **gRPC/HTTP 推理服务**（TorchServe / ONNX Runtime / TF Serving，二选一起步） | 实现 `RankModelClient.predict(RankingFeatures)`，把 record 序列化成请求体    |
| 在线特征拉取  | 单次请求批量取候选的特征（`realtimeMatch`/ 作者健康分等）                            | 复用现有填充 `RankingFeatures` 的代码路径，不新建特征拉取                            |
| 超时 / 降级 | 推理超时（建议 ≤80ms，对齐蓝图降级口径）→ 回本地 FM                                  | `DeepFmRankingModel` 已内置 remote 异常回退，**只需补实现 bean**               |

**改动点**：新增 `RankModelClient` 的 HTTP/gRPC 实现 bean（带超时、连接池、fail-open）+ 离线训练 job 导出模型权重 + `RankModelType=DEEPFM` 且 `remote-model-enabled=true` 切换。

**验收（A/B）**：真模型 vs 本地确定性 FM，互动率 / 完播 / 时长是否显著提升；P99 推理耗时达标。

### P3：规模化（量级上来再做）



| 动作      | 说明                                                                      |
| ------- | ----------------------------------------------------------------------- |
| ANN 扩容  | RediSearch KNN 到量级瓶颈后迁 Milvus / Qdrant（`VectorIndexWriter` 已抽象写入，可替换实现） |
| 特征平台    | 在线 / 离线特征同源（特征 store），消除训练 - serving 偏差                                 |
| 自动训练流水线 | 定时离线训练 → 向量 / 模型灰度发布 → 监控漂移                                             |
| 召回粗排    | 粗排模型截断候选，精排只对 top-N 打分                                                  |



***

## 4. 关键设计决策（选型与依据）



| 问题         | 结论                                                            | 否决项                   |
| ---------- | ------------------------------------------------------------- | --------------------- |
| 训练框架       | P1 用轻量 i2i/ALS（可先 Java/Spark 小 job）；P2 上 PyTorch（DeepFM / 双塔） | 不一上来就 Flink / 在线学习    |
| 向量存储       | 先复用 Redis + RediSearch KNN（dim=64）                            | 不为可能的需求提前引 Milvus     |
| 模型 serving | 独立推理服务，经 `RankModelClient` 接入                                 | 不在 Java 进程里内嵌训练 / 推理库 |
| 特征一致性      | `RankingFeatures` 为唯一特征契约，在线填充路径与离线特征重算同源                     | 业务对象直接喂模型             |
| 一致性        | 离线产物 → Redis / 模型服务；在线读路径零改                                   | 回写业务库                 |
| 降级         | 每条 ML 路径都有规则兜底                                                | 模型挂 = 推荐挂             |



***

## 5. 在线链路与特征契约（对接现有代码）



* **召回**：`VectorRecallChannel` 保持不变，只是其底层 item/user 向量从 "标签均值" 换成 "行为训练"。

* **精排**：


  * `DeepFmRankingModel.score(f)` 已实现 "remote-enabled 且有 bean → `client.predict(f)`，异常回本地 FM"；

  * 你要做的唯一新代码是**提供&#x20;**`RankModelClient`**&#x20;bean**，把 `RankingFeatures` 序列化发给推理服务。

* **特征填充**：现有 `FeedTimelineStore.readPage` 已经在构造 `RankingFeatures`（interestMatch/shortTerm/session/realtime/ 作者健康分）—— 模型直接消费这些特征，**收藏等新信号会自动成为特征的一部分**（收藏标签进画像 → interestMatch 升高）。



***

## 6. 降级、一致性与可观测



| 场景           | 对策                                                              |
| ------------ | --------------------------------------------------------------- |
| 远程模型超时 / 异常  | fail-open 回本地确定性 FM（`DeepFmRankingModel` 已内置）                   |
| 向量索引丢失       | 退回 tag 向量 / 规则召回（`TagItemEmbeddingSource`）                      |
| 向量 / 模型版本不一致 | 向量 KV 与模型权重打版本号，整体发布、整体回滚                                       |
| 特征缺失         | 各特征字段缺失 = 0（fail-open，不增强也不打压）                                  |
| 训练数据漂移       | 离线样本与线上分布对账；监控召回 / 精排分布                                         |
| A/B          | 按 uid 哈希分桶，对比规则 vs 模型流的互动率 / 时长 / 留存（**P0 就要补这个最简分桶，否则模型无法证伪**） |



***

## 7. 改动清单（对接现有类）



| 模块        | 新增 / 改动                                                         | 说明                                  |
| --------- | --------------------------------------------------------------- | ----------------------------------- |
| 数据        | 打开 `behavior-log`；固化 `interaction_event` 样本                     | P0，配置 + 离线取数脚本                      |
| 离线        | 新增训练 job（i2i → 双塔 → DeepFM）                                     | 产出 item 向量 / 模型权重                   |
| `recall`  | 新增 `BehaviorUserEmbeddingSource implements UserEmbeddingSource` | 行为聚合用户向量；写回复用 `tf:emb:*`/`tf:vec:*` |
| `ranking` | 新增 `HttpRankModelClient implements RankModelClient`             | gRPC/HTTP 推理，带超时 / 连接池 /fail-open   |
| `ranking` | 配置 `remote-model-enabled=true`、`rank-model-type=DEEPFM`         | 热切换，读路径零改                           |
| 索引        | `VectorIndexWriter` 复用（P3 可替换为 Milvus 实现）                       | 写入 / 检索抽象保持                         |
| 可观测       | 最简 A/B 分桶 + 模型耗时 / 命中率指标                                        | 模型收益可证伪                             |



***

## 8. 验收（分期，能量化）



| 期  | 能回答的业务问题                                                  |
| -- | --------------------------------------------------------- |
| P0 | 能否导出 N 天、含正 / 负样本与 requestId/position 的训练集？               |
| P1 | 行为训练向量 vs 标签向量，召回 hitRate/NDCG 是否更高？个性化流互动率是否提升（A/B）？     |
| P2 | 真 DeepFM vs 本地确定性 FM，互动率 / 完播 / 时长是否显著提升？推理 P99 是否 ≤80ms？ |
| P3 | 模型相对规则的长期留存收益是否稳定？向量 / 模型能否灰度发布与回滚？                       |



***

## 9. 明确不做



1. **P1 之前不上 GPU / 双塔**—— 先用 i2i 把数据和召回跑通。

2. **不在 Java 进程内嵌推理**—— 独立模型服务，经 `RankModelClient` 接入。

3. **不为可能的需求提前引 Milvus/Flink/ 特征平台**——Redis+RediSearch 起步，量级到了再迁。

4. **不让模型挂掉影响推荐**—— 每条 ML 路径都有规则兜底。

5. **不做无 A/B 支撑的模型上线**—— 没有分桶就不发模型。



***

## 10. 风险与前置



| 项               | 说明                                          |
| --------------- | ------------------------------------------- |
| 数据不足            | DAU 未到量级时 i2i / 双塔样本稀疏，先靠 tag 向量 + 规则，别硬上模型 |
| 训练 - serving 偏差 | 特征必须同源（`RankingFeatures` 契约），离线特征重算与在线一致    |
| 模型可解释性          | 权重 /embedding 可导、可回滚；失败即退规则                 |
| 工程成本            | 独立推理服务 + 训练 job 需要运维人力，量级和收益未达前不启动          |



***

*一句话：仓库已留好&#x20;*`RankModelClient`*、*`VectorRecallChannel`*、*`RankingFeatures`*&#x20;三处 "缝"；引入 ML 就是先把数据地基补上，再把 "标签均值向量" 和 "确定性 FM" 换成 "用真实行为训练出来的产物"，在线读路径零改动、全程 fail-open。*