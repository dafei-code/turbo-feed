# 0044 本机 Redis 收拢：单机下线，集群成为唯一实例

## 变更

| 项 | 内容 |
|---|---|
| 下线 | 单机 Redis 容器（6379）已停止并删除。删除前 BGSAVE 备份至 `/Volumes/D/workbuddy/backups/redis-single-dump-20260921.rdb`（内容仅 3 个过期测试时间线键 / 1.57MB，无迁移价值，未迁移） |
| 保留 | `deploy/redis/docker-compose-cluster.yml` 3 主 3 从（7000-7005）成为本机唯一 Redis |
| 加固 | 6 个节点补 `restart: unless-stopped`——原默认策略是 `no`，宿主机/Docker 重启后集群不会自己回来，而它现在是唯一实例 |
| 验证 | 容器重建后 `cluster_state:ok`、16384 槽全覆盖、读写正常；网关以 `mode=cluster` 启动、登录返回 token、日志 0 处 Redis 错误 |

## 为什么此前有两个 Redis

单机 6379 是项目默认拓扑（`turbofeed.redis.mode` 默认 `single`）；7000-7005 集群是 0043
为验证切换路径搭的。两者并存是因为默认值仍指向 single——集群当时只是「验证过的备选」，
不能在没切换前就删掉正在被默认使用的那个。

## 对本机日常开发的影响

IDEA 运行配置需加两个环境变量（其余不变）：

```
TURBOFEED_REDIS_MODE=cluster
TURBOFEED_REDIS_CLUSTER_NODES=127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002
```

仓库默认值**保持 single 不变**（克隆即跑 + 单点足够日常开发；集群是「验证过的升级路径」，
不是强制依赖，见 ops/deployment.md）。

## 注意

- 集群节点 announce 的是宿主机局域网 IP，**换网络环境（换 Wi-Fi）后需重设
  `REDIS_CLUSTER_ANNOUNCE_IP` 并重建集群**（`down -v` 后重新 create）。
- 推荐与画像的在线特征将复用该集群（见 `docs/architecture/recommendation-design.md`）。
