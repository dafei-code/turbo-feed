# 0050 · Phase 1b 兴趣画像真·Redis 集群验证 + weightedTags CCE 修复

日期：2026-09-28　前置：0049（Phase 1b 兴趣标签）

## 背景

0049 的逻辑级验证（`target/verify/Phase1bVerify.java`）只覆盖解析与打分公式，未触真实 Redis。
本次把 `TagIndexService` / `InterestService` 挂到本地 Docker Redis Cluster（3 主 3 从，
`192.168.0.6:7000-7005`，密码走环境变量）做定向真·验证。

## 前置：集群因局域网 IP 漂移重建

容器健康但 `cluster_state:fail`、其余节点全 `fail?`——建群时 `cluster-announce-ip=192.168.0.4`，
当前 IP 已漂移为 `192.168.0.6`（compose 文件头警告过的坑）。按文档流程清空重建：
`down -v` → 新 IP `up -d` → `--cluster create` → `cluster_state:ok`、16384 槽全 ok。

## 发现并修复的 Bug

`InterestService#weightedTags` 原实现 `((Number) e.getValue()).doubleValue()`。
`StringRedisTemplate` 的 Hash 读出值是 `String`（如 `"2.7"`），强转 `Number` 抛
`ClassCastException` → 被 fail-open catch 吞掉 → **画像永远读成空，兴趣加成在生产上不会生效**。
写入路径（`HGETALL` 可见 `java=2.7/后端=2.5/测试=0.2`）完全正常，只有读取侧坏。

修复：`double v = Double.parseDouble(String.valueOf(e.getValue()));`

教训：fail-open 的静默吞异常会把「读路径全坏」伪装成「没有画像（冷启动）」；
逻辑级验证无真 Redis 抓不到这类类型契约问题，**带 Redis 依赖的代码必须真库验证一次**。

## 验证（`target/verify/Phase1bRedisVerify.java`，真集群，10/10 PASS）

- 标签双向索引：`index` → `tagsOf` / `mediaWithTag`；`remove` 摘除双侧索引。
- 兴趣累积：LIKE+1.0 / COMMENT+1.5 / WATCH+0.2（同内容多标签各加一份，java=2.7 实证）。
- IMPRESSION 不计权；DISLIKE 负值留存但 `weightedTags` 只出正向（负向不进召回）。
- 冷启动：匿名 / 无画像用户均空 Map、不报错。

## 未决

- 全链路 e2e（上传→过审→点赞→推荐前置）仍需 MinIO/RocketMQ/双 MySQL 数据源 + 双应用拉起，另案。
