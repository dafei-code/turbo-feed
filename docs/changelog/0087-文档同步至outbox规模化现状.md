# 0087 文档同步至 outbox 规模化现状

- **日期**：2026-11-27
- **类型**：docs
- **动机**：P0–P3（大 V 治理、作者索引分片、outbox CDC、outbox 128 分片）已落地并真启动验证，但 GitHub 门面 README 与 docs 架构/运维文档仍保留 11/13/15 张表、CDC 未部署、tf-counter 骨架等旧口径。

## 改动

1. `README.md`：分片逻辑表改为 **16 张（7×hashMod4 + 9×hashMod128）+ 12 张单表**；补 Canal、outbox CDC、SENT 清理、关注流大 V 治理与新增环境变量；运维文档入口改为 13 篇。
2. `docs/README.md`：同步 16 张分片逻辑表口径、模块状态与 outbox 分片/CDC 阅读路径。
3. `docs/architecture/sharding.md`：补齐 16 张逻辑表的分片键表、1180 张物理表分布、`outbox_event` 按雪花 `id` 分片的原因与迁移链接。
4. `docs/architecture/overview.md`：更新模块完成度、技术栈、通信矩阵与演进路线；标注 Nacos opt-in、Prometheus 指标、Canal CDC 默认关闭。
5. `docs/modules/tf-feed-engine.md`：补推荐漏斗、关注流大 V 治理、作者索引分片/L1 缓存、`X-Internal-Token` 鉴权与相关配置。
6. `docs/ops/deployment.md`：全栈 compose、Canal 部署、CDC/清理器开关、环境变量与生产前清单同步。
7. `docs/ops/outbox-cdc-rollout.md` / `outbox-sharding-rollout.md`：修正“未部署/非完整启动/备份表暂留”等过期表述，回填端到端真启动验证与备份表已清理。
8. `docs/adr/0011-cdc-clickhouse.md` / `docs/adr/README.md` / `docs/architecture/recommendation-design.md`：明确 outbox CDC 已落地，但不改变 ClickHouse 全表出仓与 T+1 画像的 Proposed 状态。

## 影响

- 纯文档同步，不改代码、不改配置、不改运行时行为。
- 不替代任何生产灰度动作：CDC 开关仍默认关闭，Relay 仍必须保留。

## 验证

- 按当前 `shardingsphere-config.yaml` 核对：`autoTables` 16 张（7×hashMod4 + 9×hashMod128）、`!SINGLE` 12 张。
- 按 `application.yml` 核对 CDC / SENT cleaner / 关注流治理配置键与默认值。
- 复核 MySQL `information_schema`：`outbox_event_backup` 已清理。
