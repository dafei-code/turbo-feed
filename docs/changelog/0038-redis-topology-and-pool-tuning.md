# 0038 Redis 拓扑可切换（单机 / 哨兵 / 集群）+ 连接池调优

## 一、连接池调优

原配置 `max-active: 16 / max-idle: 8 / min-idle: 2`，按当前并发上限估算偏紧：

> 上传并发上限 200（Sentinel `rate-limit.thread`）× 单请求 Redis 次数约 3
> （用户级限流 + 并发占位 + 状态缓存）× 单次命令约 0.2ms → 稳态只需 ~12 条连接。

取 4 倍余量定为 **64**，吸收抖动与偶发慢命令，避免线程在 `max-wait` 前排队。
上限约束：Redis 侧 `maxclients`（默认 10000）÷ 网关实例数——别让单实例的池吃掉全局配额。

```
lettuce.pool: max-active 16→64，max-idle 8→24，min-idle 2→8，max-wait 1000ms（不变）
```

## 二、拓扑可切换：`RedisTopologyConfig`

### 为什么不直接在 yml 里写 `spring.data.redis.cluster.nodes`

Boot 的 `RedisAutoConfiguration` 是**按属性是否存在**来选拓扑的。在默认配置里留一个空占位符，
会导致「既不是单机也不是集群」，行为取决于绑定后是 `null` 还是空集合——不可预测。
改成显式开关 `turbofeed.redis.mode`：

| mode | 行为 |
|---|---|
| `single`（默认） | 本配置**一个 Bean 都不注册**，完全交给 Boot 自动装配，与改造前逐字节一致 |
| `cluster` | 注册 `LettuceConnectionFactory`（集群），顶替 Boot 的（其带 `@ConditionalOnMissingBean`） |
| `sentinel` | 同上，哨兵拓扑 |

节点地址一律来自环境变量注入（`TURBOFEED_REDIS_CLUSTER_NODES` 等），
**默认配置里不出现任何内网 IP**——与 JWT 密钥、MinIO 凭据同一纪律。

### 参数单一来源

连接池参数**复用** `spring.data.redis.lettuce.pool.*`，不另起一套，
避免「单机一套、集群另一套」的漂移——切换拓扑时池的行为保持不变。

### 失败即停（fail-fast）

`mode=sentinel` 但没给 master 名、或 `mode=cluster` 但没给节点 → **启动直接抛异常**。
绝不静默回落到单机，那等于「假装高可用」。

## 三、必须说清楚的一件事：**这次没有真的上集群**

Redis Cluster 要容忍单节点故障至少 3 主 3 从，Sentinel 至少 3 个哨兵才能避免脑裂误判。
本机 docker 只起了**一个** Redis，所以默认保持 `single`。

本次交付的是「**切换路径已就绪且被验证**」，不是把单机包装成集群。
真要上集群，运维侧需要先补齐节点，再把 mode 与节点地址注入即可，代码零改动。

## 四、验证

`mode=single`（默认）下 MQ profile 启动成功，Redis 相关组件无异常，
登录/发件箱中继等依赖 Redis 的路径均正常——确认「默认路径零回归」。

（集群 / 哨兵分支因本机无对应拓扑未做运行时验证；其为 `@ConditionalOnProperty` 隔离，
默认不装配，不影响单机路径。）

## 改动清单

- 新增 `config/RedisTopologyConfig.java`
- `application.yml`：池参数调优 + 新增 `turbofeed.redis.*`（mode / cluster-nodes / sentinel-master / sentinel-nodes / max-redirects）
