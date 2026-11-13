"""
turbo-feed 内容安全语义层 sidecar。

契约：
  POST /score    body {"text": "...", "userId": 0}
                 → 200 {"score": 0.0~1.0, "nearestWord": "...", "category": "...", "modelVersion": "..."}
  GET  /health   → {"ok": true, "model": ..., "exemplars": N}
  POST /reload   → 重新从 MySQL 加载坏样本

打分实现（当前仅一档在跑）：
  字符 n-gram hashing vector（256 维，确定性，零权重下载）+ cosine，
  对插入字变体有一定鲁棒性。
真模型（sentence-transformers bge-small-zh）是预留接入点：需取消 requirements.txt
注释并手动把 embed() 换成 model.encode(text)，运行时不会自动切换。

坏样本来源：MySQL `moderation_exemplar` 表（enabled=1）；连不上时用环境变量
FALLBACK_EXEMPLARS 注入的兜底列表，未配置则无样本（/score 返回 0.0，fail-open）。
"""
import hashlib
import os
import time
from typing import List, Tuple

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="turbo-feed-moderation-sidecar", version="0.2.0")

# ---------- 配置 ----------
VEC_DIM = 256
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "")
DB_NAME = os.environ.get("DB_NAME", "turbo_feed_1")

# 兜底坏样本：词条属于运营数据，禁止写进仓库默认配置。
# 仅允许通过环境变量注入，格式 FALLBACK_EXEMPLARS='分类:文本|分类:文本'。
# 未配置时为空列表：reload_index() 得到 0 条样本，/score 返回 0.0（fail-open），
# /health 的 exemplars=0 让「语义层当前全部放行」这件事可见。
def _load_fallback_exemplars():
    raw = os.environ.get("FALLBACK_EXEMPLARS", "").strip()
    if not raw:
        return []
    items = []
    for entry in raw.split("|"):
        entry = entry.strip()
        if ":" not in entry:
            continue
        category, sample_text = entry.split(":", 1)
        sample_text = sample_text.strip()
        if sample_text:
            items.append((sample_text, category.strip() or "DEFAULT"))
    return items


FALLBACK_EXEMPLARS = _load_fallback_exemplars()

# 内存中的坏样本向量库：[(vec, text, category)]
exemplar_index: List[Tuple[List[float], str, str]] = []
MODEL_VERSION = "hash-ngram-256-v1"
DB_CONNECTED = False


# ---------- 确定性 hashing 向量（零权重）----------
def _ngrams(text: str) -> List[str]:
    t = "".join(text.split())
    grams = []
    for n in (1, 2, 3):
        for i in range(len(t) - n + 1):
            grams.append(t[i:i+n])
    return grams


def embed(text: str) -> List[float]:
    vec = [0.0] * VEC_DIM
    for g in _ngrams(text):
        h = int(hashlib.md5(g.encode("utf-8")).hexdigest(), 16)
        vec[h % VEC_DIM] += 1.0
    # L2 归一化
    norm = sum(v * v for v in vec) ** 0.5
    if norm > 0:
        vec = [v / norm for v in vec]
    return vec


def cosine(a: List[float], b: List[float]) -> float:
    return sum(x * y for x, y in zip(a, b))  # 两边都已归一化


# ---------- 坏样本加载 ----------
def load_exemplars_from_db() -> List[Tuple[str, str]]:
    try:
        import pymysql
    except ImportError:
        return []
    try:
        conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                               password=DB_PASSWORD, database=DB_NAME,
                               charset="utf8mb4", connect_timeout=2)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT exemplar_text, category FROM moderation_exemplar WHERE enabled = 1")
                return [(r[0], r[1] or "DEFAULT") for r in cur.fetchall()]
        finally:
            conn.close()
    except Exception as e:
        print(f"[sidecar] MySQL 不可用，用兜底样本: {e}")
        return []


def reload_index():
    global exemplar_index, DB_CONNECTED, MODEL_VERSION
    rows = load_exemplars_from_db()
    DB_CONNECTED = len(rows) > 0
    if not rows:
        rows = FALLBACK_EXEMPLARS
    exemplar_index = [(embed(t), t, c) for t, c in rows]
    print(f"[sidecar] 样本库已加载: {len(exemplar_index)} 条 (db={DB_CONNECTED})")


# ---------- 请求模型 ----------
class ScoreReq(BaseModel):
    text: str
    userId: int = 0


@app.on_event("startup")
def startup():
    reload_index()


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_VERSION, "exemplars": len(exemplar_index),
            "db": DB_CONNECTED, "ts": int(time.time())}


@app.post("/reload")
def reload():
    reload_index()
    return {"ok": True, "exemplars": len(exemplar_index), "db": DB_CONNECTED}


@app.post("/score")
def score(req: ScoreReq):
    text = (req.text or "").strip()
    if not text or not exemplar_index:
        return {"score": 0.0, "nearestWord": None, "category": None, "modelVersion": MODEL_VERSION}
    q = embed(text)
    best_score, best_word, best_cat = 0.0, None, None
    for vec, word, cat in exemplar_index:
        s = cosine(q, vec)
        if s > best_score:
            best_score, best_word, best_cat = s, word, cat
    return {
        "score": round(best_score, 4),
        "nearestWord": best_word,
        "category": best_cat,
        "modelVersion": MODEL_VERSION,
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", "7800"))
    uvicorn.run(app, host="0.0.0.0", port=port)
