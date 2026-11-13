# 内容安全语义层 sidecar

独立 HTTP 服务，给 Java 网关打语义分。Java 侧 `HttpSemanticScorer` 调 `POST /score`。

## 跑起来

```bash
# 本地（无 MySQL 时自动用兜底样本）
pip install -r requirements.txt
python app.py
# → http://127.0.0.1:7800

# Docker
docker build -t turbofeed-moderation-sidecar .
docker run -p 7800:7800 \
  -e DB_HOST=host.docker.internal \
  -e DB_USER=root -e DB_PASSWORD=xxx \
  -e DB_NAME=turbo_feed_1 \
  turbofeed-moderation-sidecar
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活 + 样本数 + 是否连到 MySQL |
| POST | `/score` | body `{"text":"...","userId":0}` → `{"score":0.0,"nearestWord":"...","category":"...","modelVersion":"..."}` |
| POST | `/reload` | 改完 `moderation_exemplar` 表后热加载 |

## Java 侧开关

```yaml
turbofeed:
  content-security:
    semantic:
      enabled: true
      endpoint: http://127.0.0.1:7800
      timeout-ms: 100
      block-threshold: 0.86   # 相似度≥此值 BLOCK
      review-threshold: 0.78   # ≥此值 REVIEW
```

## 打分实现（两档降级）

1. **当前档**：字符 1~3-gram → MD5 hashing 到 256 维向量 → cosine。确定性、零权重下载、抗插入字。
2. **生产档**（预留）：`requirements.txt` 里取消注释 `sentence-transformers` + `torch`，把 `embed()` 换成 `model.encode(text)` 即可，对外契约不变。

阈值需要用真实样本校准：拿历史 CONFIRMED 命中和 REJECTED 误杀样本各 200 条，画 score 分布 ROC，再定 block/review 两个阈值。
