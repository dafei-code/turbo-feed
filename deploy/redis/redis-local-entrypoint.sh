#!/bin/bash
# 本地永逸 Redis Cluster 启动入口：单容器内起 6 个 redis 进程（7000-7005），
# 全部 cluster-announce-ip=127.0.0.1，客户端端口发布到宿主 127.0.0.1。
# 127.0.0.1 永远不变 → 彻底规避 Mac 局域网 IP 漂移。
set -e

PASS="${REDIS_PASSWORD:-123456}"

# 1) 启动 6 个 redis 进程（各自独立子目录，避免 AOF / nodes 文件互相踩踏）
for p in 7000 7001 7002 7003 7004 7005; do
  mkdir -p "/data/$p"
  redis-server --port "$p" \
    --cluster-enabled yes \
    --cluster-config-file "/data/$p/nodes-$p.conf" \
    --cluster-node-timeout 5000 \
    --appendonly yes --dir "/data/$p" \
    --requirepass "$PASS" --masterauth "$PASS" \
    --cluster-announce-ip 127.0.0.1 \
    --cluster-announce-port "$p" \
    --cluster-announce-bus-port "1$p" &
done

# 2) 等待全部节点就绪（最多 ~60s，避免单节点起不来时死循环）
for p in 7000 7001 7002 7003 7004 7005; do
  n=0
  until redis-cli -h 127.0.0.1 -p "$p" -a "$PASS" ping 2>/dev/null | grep -q PONG; do
    n=$((n + 1))
    if [ "$n" -ge 200 ]; then echo "node $p not ready, abort"; exit 1; fi
    sleep 0.3
  done
  echo "node $p ready"
done

# 3) 幂等建集群：未形成则 reset + create（已存在则跳过）
if ! redis-cli -h 127.0.0.1 -p 7000 -a "$PASS" cluster info 2>/dev/null | grep -q "cluster_state:ok"; then
  echo "forming cluster..."
  for p in 7000 7001 7002 7003 7004 7005; do
    redis-cli -h 127.0.0.1 -p "$p" -a "$PASS" cluster reset 2>/dev/null || true
  done
  redis-cli -h 127.0.0.1 -p 7000 -a "$PASS" --cluster create \
    127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 \
    127.0.0.1:7003 127.0.0.1:7004 127.0.0.1:7005 \
    --cluster-replicas 1 --cluster-yes 2>&1 || echo "cluster create failed, see above"
fi

# 4) 保持容器存活：等待任意后台进程退出
wait
