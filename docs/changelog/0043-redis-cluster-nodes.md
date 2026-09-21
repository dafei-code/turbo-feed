# 0043 Redis 集群节点落地：3 主 3 从（本机验证环境）

## 背景：0038 只是「切换路径就绪」，不是真上了集群

0038 做的 `RedisTopologyConfig`（`turbofeed.redis.mode` = single / cluster / sentinel）
此前**只在 single 下跑过**——本机只有一个 Redis，集群分支从未被执行。
这是典型的「代码写好了但没验证」：`mode=cluster` 到底能不能连通、MOVED 重定向、
池化配置是否兼容，全靠猜。本次把节点补齐并真跑一遍。

## 改动

新增 `deploy/redis/docker-compose-cluster.yml`：6 个节点（7000-7005），
`--cluster-enabled yes`，客户端端口 7000-7005 + 集群总线 17000-17005。

### ⚠️ 最大的坑：`cluster-announce-ip` 填什么

| 填法 | 结果 |
|---|---|
| `127.0.0.1` | ❌ 容器间互联会连到自己（每个容器的 127.0.0.1 是它自己），握手失败 |
| `host.docker.internal` | ❌ 容器→宿主可以，但 **Mac 宿主本身解析不了这个名字**，宿主机 JVM 收到 MOVED 后连不上其它节点 |
| **宿主机局域网 IP**（本机 192.168.0.4） | ✅ 容器→宿主、宿主→容器双向都通 |

代价：局域网 IP 会随网络变化，换 Wi-Fi 需重设 `REDIS_CLUSTER_ANNOUNCE_IP` 并重建集群。
compose 里用 `${REDIS_CLUSTER_ANNOUNCE_IP:?...}` 强制显式传入，避免静默用错。

## 验证

| 项 | 结果 |
|---|---|
| 建集群 | 3 主（7000/7001/7002）+ 3 从（7003/7004/7005），`All 16384 slots covered` |
| `RedisTopologyConfig` 集群 Bean（**真实 Bean，非复刻代码**） | `LettuceConnectionFactory` 装配成功，跨槽写入/读回 **60/60** |
| 真实网关以 `mode=cluster` 启动 | `Started GatewayApplication`，并**经 Redis Cluster 完成失效频道订阅** |

应用侧切换方式（不改 `application.yml` 默认值，走环境变量）：

```bash
TURBOFEED_REDIS_MODE=cluster \
TURBOFEED_REDIS_CLUSTER_NODES=192.168.0.4:7000,192.168.0.4:7001,192.168.0.4:7002
```

客户端会自动发现其余节点，不必列全 6 个。

## 已知问题（不修，记录待观察）

关闭连接工厂时有两处 WARN：

```
LettucePoolingConnectionProvider contains unreleased connections
Cannot properly close cluster command executor
PoolException: Returned connection ... does not belong to this connection provider
```

这是 **Lettuce 池化 + Redis Cluster 连接**的已知交互：集群的多节点命令执行器会按节点借连接，
池化提供器在释放时认不出这些连接。目前只在**关闭阶段**出现，正常运行期未观测到泄漏或报错。

若后续在集群模式下遇到池耗尽，优先尝试「集群模式不启用池化」——
Lettuce 的 cluster connection 本身是线程安全且多路复用的，
共享一条（`shareNativeConnection=true`）反而更契合集群语义。
**本次不改**：没有运行期故障证据，不做预防性行为变更。

## 状态

默认仍是 `single`。上集群的前提是**真的有 3 主 3 从**——
1 主 0 从的集群比单点更脆（槽位未全覆盖会直接拒绝写入），那是「假装高可用」。
本机集群仅用于验证切换路径，不影响 `application.yml` 默认值。
