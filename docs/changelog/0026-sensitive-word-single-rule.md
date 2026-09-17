# 0026 · 修复敏感词表路由缺失（ShardingSphere !SINGLE 规则）

> 日期：2026-09-17
> 范围：`tf-gateway`（配置 + 注释，零业务代码改动）
> 前置：`0023-post-review-deadlock-cas.md`（敏感词模块引入）
> 关联：`docs/architecture/sharding.md`

## 一、背景：一次「静默失效」的启动报错

网关启动日志出现：

```
ERROR c.t.g.s.moderation.SensitiveWordService : 敏感词库启动加载失败（保留空 Trie）:
Table or view 'sensitive_word' does not exist.
org.apache.shardingsphere.infra.exception.kernel.metadata.TableNotFoundException
```

真正的风险不在报错本身，而在后果链：`@PostConstruct` 把异常 catch 住后保留
`AhoCorasick.EMPTY` → 所有 `requireClean()` 对**全部文本放行**——fail-closed 设计
静默失效，日志里只剩一行 ERROR，极易漏判。

## 二、根因取证（ShardingSphereDriver 直连真实 yaml，五组对照）

`sensitive_word` 物理表确实存在（仅 `turbo_feed_1`，0 行），但
`shardingsphere-config.yaml` 的 `rules:` 只有 `!SHARDING`：

| # | 配置形态 | 实测结果 |
|---|---|---|
| 1 | 现状（只有 `!SHARDING`） | ❌ `TableNotFoundException`（复现报错） |
| 2 | + `!SINGLE` 仅 `defaultDataSource: ds_0` | ❌ 同样失败——**只配默认数据源无效** |
| 3 | + `!SINGLE`，`tables: [sensitive_word]`（无库前缀） | ❌ `InvalidDataNodeFormatException` |
| 4 | + `!SINGLE`，`tables: [ds_0.sensitive_word]` + `defaultDataSource: ds_0` | ✅ 查询成功 |
| 5 | + `!SINGLE`，仅 `tables: [ds_0.sensitive_word]` | ✅ 查询成功 |

**结论**：ShardingSphere 5.5.3 不会把「物理库存在但未纳入任何规则」的表放进逻辑
元数据。`init-local.sql:378` 与 `SensitiveWordRepository` 类注释中
「未配置规则的表走默认 ds_0」的说法**不成立**（两处错误注释，本变更一并更正）。

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `tf-gateway/src/main/resources/shardingsphere-config.yaml` | `rules:` 下新增 `- !SINGLE`：`defaultDataSource: ds_0` + `tables: [ds_0.sensitive_word]`，附坑位说明注释 |
| `tf-gateway/src/main/resources/shardingsphere-config-128.yaml` | 同上（切换 128 分片方案时不再复踩） |
| `tf-gateway/.../moderation/SensitiveWordRepository.java` | 类注释更正为实测结论，指明新增单表必须登记 `!SINGLE` |

写法要点（均已实测验证）：

- `tables` 元素必须是**「数据源.表名」全限定格式**，只写表名抛
  `InvalidDataNodeFormatException`；
- `defaultDataSource` 决定「未显式列出的新建单表」落到哪个库，对本查询非必需，
  保留 `ds_0` 与建表脚本意图一致。

## 四、验证（端到端，Redis/MinIO/MySQL 本地全在线）

1. **探针**：改后的真实主配置 → `SELECT COUNT(*) FROM sensitive_word` 成功（count=0），
   `SELECT COUNT(*) FROM user` 正常返回（分片路由无回归）。
2. **真实启动**：`Started GatewayApplication in 9.2s`，全日志 **0 条 ERROR**，
   `敏感词 AC 重建完成: trieSize=0, dbCount=0`——异常路径彻底消失。
3. **写链路**（admin API，演示账号 13800138000）：
   加词 → `dbCount=1, acLoadedSize=1`（INSERT 路由 + AC 热重建全通）→ 删除 → 归零；
   MySQL 直连复核 0 行，测试数据已清理。

## 五、遗留与说明

- **词库为空是预期状态**：`init-local.sql` 刻意不写种子词（遵循「默认配置不含
  具体/demo 值」约束），正式生效需经 `AdminSensitiveWordController`（`/api/admin/moderation/words`）
  或后台页面加词。
- `shardingsphere-config-128.yaml` 存在**既有**问题（与本变更无关，未处理）：
  手动 `tables` 引用 `HASH_MOD`（自动分片算法），启动即抛
  `AlgorithmInitializationException: tables sharding configuration can not use auto
  sharding algorithm`。该文件切换前需另行修复。
- 启动期敏感词加载失败目前仍为「catch + ERROR 日志 + 空 Trie」的软失败策略；
  如需改为启动显式失败或更醒目告警，另行变更。
