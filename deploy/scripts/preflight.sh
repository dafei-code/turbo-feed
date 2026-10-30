#!/usr/bin/env bash
# =============================================================================
# turbo-feed 启动前校验（preflight）
#
# 用法：
#   bash deploy/scripts/preflight.sh [env文件路径]      # 默认 deploy/env/.env
#
# 退出码：0 = 可以启动；1 = 存在阻断性问题
#
# ── 为什么需要它 ──────────────────────────────────────────────────────────────
#   compose 里的 ${VAR:?} 只能保证「非空」，管不了「值对不对」：
#   JWT 密钥给 8 个字符 compose 照样放行，直到第一个用户登录才在运行期炸；
#   Redis 选 cluster 却没给节点列表，服务能起来但一直连不上。
#   这些错误晚发现的代价远高于启动前扫一遍。
#
#   本脚本刻意不做的事：不校验远端服务是否可达（那是启动后 healthcheck 的职责），
#   不打印任何密钥值（CI 日志与终端回滚都可能泄露）。
# =============================================================================
set -u

ENV_FILE="${1:-deploy/env/.env}"
ERR=0
WARN=0

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
fail()  { red   "  ✗ $*"; ERR=$((ERR + 1)); }
warn()  { yellow "  ! $*"; WARN=$((WARN + 1)); }
ok()    { green "  ✓ $*"; }

if [ ! -f "$ENV_FILE" ]; then
  red "找不到 env 文件: $ENV_FILE"
  echo "先执行: cp deploy/env/.env.example $ENV_FILE"
  exit 1
fi

# set -a 让 source 进来的变量自动 export，供后续检查与子进程使用
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

echo "校验 $ENV_FILE"
echo

# set -u 下对「文件里根本没写这个键」的变量做 ${#VAR} 会直接终止脚本，
# 所以先统一补空串：让缺失表现为一条 fail 提示，而不是脚本莫名其妙退出。
TURBOFEED_JWT_SECRET="${TURBOFEED_JWT_SECRET:-}"
TURBOFEED_INTERNAL_TOKEN="${TURBOFEED_INTERNAL_TOKEN:-}"
TURBOFEED_REDIS_PASSWORD="${TURBOFEED_REDIS_PASSWORD:-}"
TURBOFEED_MEDIA_PUBLIC_URL_BASE="${TURBOFEED_MEDIA_PUBLIC_URL_BASE:-}"

# ── 1. 必填项非空 ────────────────────────────────────────────────────────────
echo "[1/5] 必填项"
REQUIRED="TURBOFEED_DB_USERNAME TURBOFEED_DB_PASSWORD TURBOFEED_JWT_SECRET \
TURBOFEED_INTERNAL_TOKEN TURBOFEED_MINIO_ACCESS_KEY TURBOFEED_MINIO_SECRET_KEY \
TURBOFEED_MEDIA_PUBLIC_URL_BASE TURBOFEED_REDIS_PASSWORD"
for name in $REQUIRED; do
  # eval 取间接引用；用 :- 兜住未定义变量（set -u 下直接展开会退出）
  val=$(eval "printf '%s' \"\${$name:-}\"")
  if [ -z "$val" ]; then
    fail "$name 为空"
  fi
done
[ "$ERR" -eq 0 ] && ok "8 项必填均非空"

# ── 2. 密钥强度 ──────────────────────────────────────────────────────────────
echo "[2/5] 密钥强度"
JWT_LEN=${#TURBOFEED_JWT_SECRET}
if [ "$JWT_LEN" -lt 32 ]; then
  fail "TURBOFEED_JWT_SECRET 仅 ${JWT_LEN} 字符，HS256 要求 ≥32（建议 openssl rand -base64 48）"
elif [ "$JWT_LEN" -lt 48 ]; then
  warn "TURBOFEED_JWT_SECRET ${JWT_LEN} 字符，够用但偏短，建议 ≥48"
else
  ok "JWT 密钥 ${JWT_LEN} 字符"
fi

TOKEN_LEN=${#TURBOFEED_INTERNAL_TOKEN}
if [ "$TOKEN_LEN" -lt 16 ]; then
  fail "TURBOFEED_INTERNAL_TOKEN 仅 ${TOKEN_LEN} 字符，建议 openssl rand -hex 32"
else
  ok "内部令牌 ${TOKEN_LEN} 字符"
fi

for pair in "TURBOFEED_DB_PASSWORD:数据库口令" "TURBOFEED_MINIO_SECRET_KEY:MinIO 口令"; do
  name=${pair%%:*}
  label=${pair#*:}
  val=$(eval "printf '%s' \"\${$name:-}\"")
  case "$val" in
    root|123456|password|minioadmin|changeme) fail "$label 是常见弱口令（${name}）" ;;
    *) : ;;
  esac
done

# ── 3. Redis 拓扑 ────────────────────────────────────────────────────────────
echo "[3/5] Redis 拓扑"
MODE="${TURBOFEED_REDIS_MODE:-single}"
case "$MODE" in
  single)
    warn "TURBOFEED_REDIS_MODE=single：单点故障。Redis 存着令牌黑名单与计数，挂了要降级"
    ;;
  cluster)
    if [ -z "${TURBOFEED_REDIS_CLUSTER_NODES:-}" ]; then
      fail "TURBOFEED_REDIS_MODE=cluster 但 TURBOFEED_REDIS_CLUSTER_NODES 为空"
    else
      ok "cluster 模式，节点: ${TURBOFEED_REDIS_CLUSTER_NODES}"
    fi
    ;;
  sentinel)
    warn "sentinel 模式：确认代码侧 sentinel 装配已配置"
    ;;
  *)
    fail "TURBOFEED_REDIS_MODE 取值非法: ${MODE}（single|cluster|sentinel）"
    ;;
esac

# ── 4. 对外地址 ──────────────────────────────────────────────────────────────
echo "[4/5] 对外地址"
case "${TURBOFEED_MEDIA_PUBLIC_URL_BASE:-}" in
  *minio:9000*|*localhost*|*127.0.0.1*)
    fail "TURBOFEED_MEDIA_PUBLIC_URL_BASE 指向内网地址，客户端会拿到打不开的图片链接"
    ;;
  https://*) ok "媒体地址为 https" ;;
  http://*) warn "媒体地址是 http，公网环境会被浏览器拦截混合内容" ;;
  *) : ;;
esac

# ── 5. 行为明细（behavior-log）───────────────────────────────────────────────
echo "[5/5] 行为明细"
# ⚠️ 只认带 FEED 的那个名字；不带 FEED 的写法会被 Spring 静默忽略。
if [ -n "${TURBOFEED_BEHAVIOR_LOG_ENABLED:-}" ]; then
  fail "检测到 TURBOFEED_BEHAVIOR_LOG_ENABLED（少了 FEED）：compose 只转发带 FEED 的名字进容器，这个设置不会生效；改成 TURBOFEED_FEED_BEHAVIOR_LOG_ENABLED"
fi
if [ "${TURBOFEED_FEED_BEHAVIOR_LOG_ENABLED:-false}" = "true" ]; then
  DIR="${TURBOFEED_FEED_BEHAVIOR_LOG_DIR:-/app/logs/behavior}"
  ok "已开启，落盘目录 ${DIR}（必须在 behavior-logs 卷内，否则容器重建即丢）"
  warn "开启只代表「写进卷」。采集到对象存储/数仓与备份策略需另行配置，见 docs/architecture/behavior-data-pipeline.md"
else
  ok "未开启（默认）。训练样本将没有明细来源"
fi

# ── 5b. 行为明细归档（P1）──────────────────────────────────────────────
# 归档的变量名也必须带 BEHAVIOR_LOG（compose 只转发带此前缀的名字进容器）。
if [ -n "${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_ENABLED:-}" ]; then
  fail "检测到 TURBOFEED_BEHAVIOR_LOG_ARCHIVE_ENABLED（少了 BEHAVIOR_LOG）：compose 只转发带此前缀的名字进容器，这个设置不会生效；改成 TURBOFEED_BEHAVIOR_LOG_ARCHIVE_ENABLED"
fi
if [ "${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_ENABLED:-false}" = "true" ]; then
  for v in TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_ENDPOINT TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_ACCESS_KEY TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_SECRET_KEY TURBOFEED_BEHAVIOR_LOG_ARCHIVE_BUCKET; do
    val=$(eval "printf '%s' \"\${$v:-}\"")
    if [ -z "$val" ]; then
      fail "归档已开启但 ${v} 为空（MinIO 端点/凭据/桶缺失，归档将一直失败）"
    fi
  done
  if [ "$ERR" -eq 0 ]; then
    ok "归档已开启：端点 ${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_MINIO_ENDPOINT}，桶 ${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_BUCKET}（key 前缀 ${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_BASE_KEY_PREFIX:-behavior}，本地保留 ${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_LOCAL_RETENTION_HOURS:-168}h）"
    warn "归档依赖 behavior-log 落盘本身已开启；两者需同时置 true"
  fi
else
  ok "归档未开启（默认）。数据只在单机卷，磁盘故障即全失——生产应开启"
fi

# ── 汇总 ────────────────────────────────────────────────────────────────────
echo
if [ "$ERR" -gt 0 ]; then
  red "校验失败：$ERR 项阻断，$WARN 项警告。修正后再启动。"
  exit 1
fi
if [ "$WARN" -gt 0 ]; then
  yellow "校验通过，但有 $WARN 项警告——确认它们是有意为之。"
else
  green "校验通过。"
fi
exit 0
