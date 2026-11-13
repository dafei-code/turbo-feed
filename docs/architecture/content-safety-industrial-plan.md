# 内容安全工业级改造方案

> 目标：把 turbo-feed 当前「规则匹配引擎」升级为「规则 + 语义 + 媒体 + 审计反馈」四层闭环的工业级内容安全系统。
> 现状：规则层（AC 自动机 + 归一化 + 白名单 + 四级动作）已达生产水准；缺语义层、媒体审核、命中审计与反馈闭环。
> 原则：不重写已有规则层；新增层全部 fail-open（模型/服务挂了不阻塞发布，规则层仍 fail-closed）；沿用 Docker，不装原生软件。

---

## 1. 目标架构

```
写路径（评论/昵称/描述/上传）
   │
   ▼
ContentSecurityService.requireClean(scene, text, userId)   ← 唯一入口（已存在）
   │
   ├─ L1 规则层（已完成）
   │    归一化 → AC 全命中 → 白名单(只减不增) → 按 severity 取最大动作
   │
   ├─ L2 语义层（新，fail-open）
   │    规则 PASS 后 → 调 sidecar 取 embedding → 与「坏样本库」做近邻余弦
   │    高相似度 → BLOCK；中相似度 → REVIEW；不阻塞写路径（超时 100ms）
   │
   ├─ L4 审计层（新）
   │    无论 PASS/BLOCK/REVIEW，命中即写 content_moderation_hit（脱敏快照）
   │
   ▼
动作合并：ruleAction vs semanticScore 取更严
   │
   ├─ BLOCK  → 抛 SENSITIVE_WORD_HIT（已存在）
   ├─ REVIEW → 放行 + 建 ReviewTask(CONTENT_REVIEW)（已存在）
   ├─ LIMIT  → 放行 + 标记 FOLDED/降权（评论已接，描述待接）
   └─ PASS   → 放行

媒体上传（MediaUploadService）
   │
   └─ ModerationDownstreamRouter（已存在，扩展）
        ├─ 抽帧（ffmpeg sidecar）→ 图像安全分类器（NSFW/暴力/政治敏感）
        ├─ 音频轨 → Whisper 转写文本 → 回灌 L1+L2
        └─ 封面图 → 图像分类器
```

---

## 2. L2 语义层（核心新增，兜规则漏放）

### 2.1 为什么需要
规则层只能命中「字面变体」。谐音、拆字、同义改写、新型黑话全部漏放。
语义层用 embedding 相似度兜「意思相近但字面不同」的变体——这是规则与 ML 的分界线。

### 2.2 技术选型
- **模型**：`BAAI/bge-small-zh-v1.5`（中文 embedding，512 维，CPU 上单句 ~5ms，可 ONNX 量化到 2ms 内）。
- **服务形态**：独立 sidecar 容器（FastAPI + sentence-transformers，或 Java 侧直接调 ONNX Runtime）。
  选 sidecar：Java 进程不拖 PyTorch，模型升级不动 JVM。
- **坏样本库**：DB 表 `moderation_exemplar`（词条/例句 + category + 预先算好的向量），启动时加载进网关内存，用与 AC 相同的 `AtomicReference` 模式热更新。
- **近邻计算**：N < 5000 时暴力余弦（512 维 × 5000 ≈ 2.5M 次乘加，~1ms）；超过再上 HNSW（Hnswlib）。

### 2.3 Java 侧接口
```java
public interface ContentScorer {
    /** 返回语义分：0~1，越接近 1 越像已知违规内容；fail-open 返回 NaN（视为不判）。 */
    SemanticScore score(String normalizedText, long userId);

    record SemanticScore(double score, String nearestWord, String category,
                         String modelVersion, long latencyMs, boolean available) {}
}
```

### 2.4 决策合并（改 ContentSecurityService.doCheck）
```java
// 规则命中后的 worst 是现有逻辑；规则未命中时，跑语义层
if (worstHit == null && properties.getSemantic().isEnabled()) {
    SemanticScore s = semanticScorer.score(normalized.text(), userId);
    if (s.available()) {
        double t = properties.getSemantic().getBlockThreshold();   // 默认 0.86
        double r = properties.getSemantic().getReviewThreshold(); // 默认 0.78
        if (s.score() >= t)      worst = BLOCK;
        else if (s.score() >= r) worst = REVIEW;
        // 命中样本记入审计（nearestWord）
    }
    // 模型不可用：available=false，直接放行，不抛异常（fail-open）
}
```

### 2.5 阈值校准
上线后用真实流量调：把「运营人工判为违规但规则放行了」的样本攒 200 条，画 PR 曲线，定 BLOCK/REVIEW 两个阈值。文档里写死默认值，注释里标注「需用真实数据校准」。

---

## 3. L3 媒体审核（短视频 Feed 必须补）

当前只有文本两路，视频画面/音频完全没审。

### 3.1 抽帧
- 上传时用 ffmpeg sidecar（容器 `tf-media-probe`）按关键帧抽 3~5 张代表帧（首帧 + 均匀间隔），存临时目录。
- 不抽全片（成本高），代表帧足够覆盖画面风险。

### 3.2 图像安全分类
- 模型：开源中文安全图像分类（如 NSFW/暴力/政治敏感三分类，可用 onnx 版开源权重）。
- 接入：sidecar 暴露 `POST /classify/image`，返回 `{label, score}`。
- 动作：score > 阈值 → BLOCK 或 REVIEW；命中即写审计表（mediaId 关联）。

### 3.3 音频转写回灌
- 抽音频轨 → Whisper small（CPU 可跑，~10x 实时）转写文本 → 回灌 L1 规则层 + L2 语义层。
- 这样「语音里骂人」也能被词库/embedding 兜住，不必单独训音频模型。

### 3.4 接入点
`MediaUploadService` 已有 `ModerationDownstreamRouter`——在它后面加 `MediaModerationStage`，上传完成后异步执行（不阻塞上传响应），命中即改 `MediaStatus` 为 REVIEW/BLOCK 并建 ReviewTask。

---

## 4. L4 审计与反馈闭环（ROI 最高，纯工程）

这是「从能拦到能运营」的关键。当前只有计数器，没有结构化命中样本，运营无法回扫、无法纠错。

### 4.1 新表 DDL
```sql
CREATE TABLE content_moderation_hit (
  id            BIGINT PRIMARY KEY,
  scene         VARCHAR(16)  NOT NULL,   -- NICKNAME/CAPTION/COMMENT/MEDIA
  user_id       BIGINT       NOT NULL,
  target_id     VARCHAR(64),             -- commentId / mediaId
  content_hash  CHAR(64),                -- 脱敏后 hash，便于去重
  snippet_masked VARCHAR(255),          -- 脱敏快照（命中区间打码，不存原文全量）
  rule_hits     JSON,                    -- [{word,category,start,end}]
  semantic_score DOUBLE,                 -- 0~1，未跑为 NULL
  nearest_word  VARCHAR(64),
  action        VARCHAR(16)  NOT NULL,   -- BLOCK/REVIEW/LIMIT/PASS(仅记录异常样本)
  review_task_id BIGINT,
  reviewer_result VARCHAR(16),           -- CONFIRMED/REJECTED/NULL(未审)
  created_at    DATETIME     NOT NULL,
  KEY idx_user (user_id),
  KEY idx_action (action, created_at)
);
```

### 4.2 反馈闭环
- REVIEWER 在队列里裁决：CONFIRMED（确实违规）/ REJECTED（误杀）。
- 裁决结果回灌三处：
  1. **误杀样本**（REJECTED）→ 自动进白名单候选，运营一键确认。
  2. **漏放样本**（CONFIRMED 但规则未拦）→ 进 `moderation_exemplar` 坏样本库，下次语义层直接兜住。
  3. **高频命中词** → 提示运营是否升级为硬词（从 REVIEW 升 BLOCK）。

这一层让系统**越用越准**，而不是永远靠人灌词。

---

## 5. L5 可观测扩展

现有 Prometheus 指标基础上，加：
- `content_security_semantic_score_distribution`（DistributionSummary，看阈值分布）
- `content_security_semantic_available`（Gauge，模型 sidecar 健康度，1/0）
- `content_security_hit_total`（按 action + layer=rule/semantic 标签）
- DashboardService 加「命中趋势 / 误杀率 / 漏放率」面板。

---

## 6. Docker 编排新增

```yaml
# deploy/moderation/docker-compose-ml.yml
services:
  tf-moderation-ml:
    image: ghcr.io/turbofeed/m moderation-text:latest  # 本地构建：FastAPI + bge-small-zh onnx
    container_name: tf-moderation-ml
    ports: ["127.0.0.1:7800:7800"]
    environment:
      MODEL: bge-small-zh-v1.5
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl -f http://localhost:7800/health"]
      interval: 10s

  tf-media-probe:
    image: jrottenberg/ffmpeg:6-alpine
    container_name: tf-media-probe
    # 上传服务通过共享卷/HTTP 调用抽帧，不常驻 HTTP（按需起）
    restart: unless-stopped
```

Java 侧配置：
```yaml
turbofeed:
  content-security:
    semantic:
      enabled: true
      endpoint: http://127.0.0.1:7800
      timeout-ms: 100
      block-threshold: 0.86
      review-threshold: 0.78
```

---

## 7. 分阶段落地与验收

| 阶段 | 内容 | 工作量 | 验收标准 |
|---|---|---|---|
| **P1 审计闭环**（先做） | content_moderation_hit 表 + 命中落库 + ReviewTask 裁决回灌白名单候选 | 1~2 天 | 每条 BLOCK/REVIEW 都能在后台查到；误杀可一键加白；漏放可一键加样本 |
| **P2 语义层** | sidecar + ContentScorer 接口 + 阈值校准 + 决策合并 | 2~3 天 | 规则放行的改写样本，语义层召回 ≥ 80%；P99 写路径新增 < 20ms；sidecar 停掉不影响发布 |
| **P3 媒体审核** | ffmpeg 抽帧 + 图像分类 + Whisper 转写回灌 | 3~5 天 | 上传涉黄/暴力视频帧能被拦截；骂人语音转写后被词库命中 |
| **P4 运营校准** | 攒真实样本调阈值、高频词升级、Dashboard | 持续 | 误杀率 < 2%，漏放率逐月下降，有数据面板可看 |

---

## 8. 风险与取舍

1. **语义层误杀风险**：embedding 阈值是双刃剑，先放 REVIEW（不直接 BLOCK）跑 2 周，看人工复核结果再决定是否升到 BLOCK。
2. **sidecar 延迟**：bge-small CPU 上单句 ~5ms，加网络/序列化 ~10ms；超时 100ms 兜底，不会拖垮写路径。
3. **模型体积**：bge-small ONNX 量化后 ~30MB，视频帧模型 ~50MB，都不进 git，用独立镜像。
4. **不做的事**：不上千万级词库/分布式词库服务（当前规模用不到）；不做多语言（先中文）；不做实时视频流审核（离线抽帧足够）。

---

## 9. 一句话总结

> **规则层已经是工业级，缺的是「让系统越用越准」的闭环。** P1 审计闭环是纯工程、最高 ROI，先做；P2 语义层兜漏放；P3 媒体审核补齐短视频场景；P4 靠运营数据校准。做完 P1+P2，这套文本审核就达到了中型内容平台的生产水准。
