# 0028 内容安全（文本）骨架落地：统一入口 + 抗绕过 + 白名单 + 有效性可见

- 日期：2026-09-18
- 范围：`tf-gateway`（代码 + 配置 + DDL）、`docs`
- 关联：[#0027 新号观察期](0027-new-user-watch-period.md)、
  [内容安全设计文档](../architecture/content-security-design.md)

## 1. 动机

审查发现文本审核的**两条链路都不生效**，且没有任何出口能暴露这件事：

1. `RuleBasedModeration.banned-keywords` 匹配的是对象名末段，而对象名由服务端拼成
   `{keyPrefix}/{uid}/{uuid}.{ext}` → **永不命中**；
2. `sensitive_word` 词库 `COUNT(*) = 0` → `requireClean` 全放行；
3. 无指标、无 health、无启动告警，接口全 200 → **静默失效**。

> 核心判断：**最大的问题不是"拦不住"，而是"拦不住这件事没人知道"。**
> 因此 S1 的第一优先级是让失效可见，而不是先堆匹配算法。

附带缺口：`nickname` 完全未过滤且无长度上限、`caption` 无长度校验（列宽 2048，超限在 DB
严格模式下抛 SQL 异常表现为 500）、词条 `category` 被丢弃无法分级处置、命中无留痕无指标。

## 2. 改动清单

### 新增（`service/moderation/`）

| 文件 | 职责 |
|---|---|
| `ContentScene.java` | 场景枚举 `NICKNAME` / `CAPTION` / `COMMENT` |
| `ModerationAction.java` | 动作枚举 `PASS` / `LIMIT` / `REVIEW` / `BLOCK`（诚实标注当前强制程度） |
| `ContentVerdict.java` | 裁定 record（动作 + 场景 + 命中词 + 分类 + 是否豁免 + 原文命中区间） |
| `SensitiveWordHit.java` | 匹配层输出（词 + 分类 + 位置） |
| `ContentSecurityProperties.java` | `turbofeed.content-security.*`（**不含任何词条**） |
| `ContentSecurityService.java` | **①统一入口 + ④决策层**，含启动自检、计数、`stats()` |
| `TextNormalizer.java` | **②预处理**：NFKC → 剥不可见 → 剥分隔符 → 小写 → 繁简，带下标回溯与两道自检 |
| `WhitelistEntry.java` | 白名单 record，含 `covers(scene, userId)`（默认拒绝） |
| `WhitelistRepository.java` | 白名单 JDBC 持久层 |
| `WhitelistService.java` | 白名单热更新（与词库同构：不可变快照 + 原子引用 + 30s 指纹） |

### 修改

| 文件 | 改动 |
|---|---|
| `AhoCorasick.java` | 新增 `Match` + `firstMatchDetail`（带回命中位置）；类注释补实测基准数据与"不引入 DAT"的结论 |
| `SensitiveWordService.java` | `Dictionary` 快照（AC + 词→分类**同一原子引用**）；新增 `match(normalized)`；**删除** `requireClean`（决策上移到 ④） |
| `CommentService.java` | 改走统一入口；长度上限归口配置，删除本地 `MAX_CONTENT_LEN` |
| `MediaUploadService.java` | 3 处 caption 改走统一入口（`upload` / `presign` / `updateCaption`） |
| `UserService.java` | **注册时新增昵称检测**（此前完全未过滤），仅检测用户显式传入的昵称 |
| `application.yml` | 新增 `turbofeed.content-security.*` 配置段 |
| `shardingsphere-config.yaml`、`-128.yaml` | `!SINGLE` 登记 `ds_0.sensitive_whitelist` |
| `deploy/mysql/init-local.sql` | 新增 `sensitive_whitelist` DDL（`COLLATE utf8mb4_bin`）；修正被实测推翻的旧注释 |

## 3. 关键决策

- **五层拆分，匹配层不抛异常**：改造前 `requireClean` 同时管匹配与拒绝，各调用方被绑死在
  "命中即拒"；拆开后策略调整只动决策层一处。写路径**只准走统一入口**。
- **白名单先于"剥分隔符"上线**：剥分隔符会显著抬高误杀（"顺 丰 车"可能被拼成敏感词），
  没有豁免出口就开这一步会让运营陷入"天天被骂但没办法"的窘境。
- **繁简映射两道自检**：编码自检（每条 2 字相异）+ 方向自检（繁→简）。
  方向写反会让简体文本被转成繁体、**所有简体词条漏放** —— 灾难级且极难观察发现。
- **白名单表用 `utf8mb4_bin`**：`sensitive_word.uk_word` 因库级 `utf8mb4_0900_ai_ci`
  大小写不敏感（实测"加 Abc 后加 abc 不新增行"），白名单是精确豁免语义，必须精确匹配，
  否则会意外豁免 `ABC`。

## 4. 验证

真实 HTTP 写路径（`/api/comments` → `CommentService` → 统一入口），词库 3 条、
白名单 1 条（`abc@COMMENT@GLOBAL`）：

| 用例 | 期望 | 实际 |
|---|---|---|
| `测试敏感词` | 拒 | ✅ 42904 |
| `测 试-敏 感词`（分隔符穿插） | 拒 | ✅ 42904，`hit=[0,8)` |
| `測 試 敏 感 詞`（繁体 + 穿插） | 拒 | ✅ 42904（**首轮失败**，见 §5 排障） |
| 零宽字符穿插 | 拒 | ✅ 42904，`hit=[0,9)` |
| `賭博` | 拒 | ✅ 42904（日志 `word=赌博`，证明繁简折叠生效） |
| `赌博` | 拒 | ✅ 42904 |
| `abc` | 放行（白名单豁免） | ✅ 200，日志"命中但被白名单豁免" |
| `ＡＢＣ`（全角 + 大写） | 放行 | ✅ 200，同上（证明 NFKC + 小写生效） |
| 1025 字符 | 参数错误 | ✅ 40001「评论过长（≤1024 字符，当前 1025 字符）」 |
| 800+ 字正常文本 | 放行 | ✅ 200（零误杀） |

启动自检日志：
```
内容安全启动自检通过: dictionarySize=3, whitelistSize=1, normalize=on(strip开 / trad开),
  繁简映射=637条(非法=0, 方向自检失败=0), 默认动作=BLOCK
```

独立探针（`TextNormalizer` 纯逻辑）：映射 637 条、非法 0、方向自检失败 0；
7 组变体归一化结果全部符合预期。

## 5. 排障记录（可复用的坑）

- **首轮失败：`測 試 敏 感 詞` 未被拦截**。根因是手工维护的繁简映射表**缺 `測/試/詞`**。
  补表至 637 条，并新增方向自检防止同类问题与"写反"复发。
- **映射表自检"非法 6 条但样本为空"**：`INVALID_PAIRS` 字段在声明处写了 `= List.of()`，
  而它在上方的 `TRAD_TO_SIMP = buildTradToSimp()` 里已被赋值 —— 声明处的初始值**晚于**
  前者执行，把结果覆盖了（静态初始化顺序）。去掉初始值后样本可见，定位到 6 条繁简同形冗余条目并删除。
- **白名单表 `TableNotFoundException`**：已改 `shardingsphere-config.yaml` 并 `cp` 到
  `target/classes/`，但运行期读到的仍是旧副本 —— `cp` 对**已存在文件**的覆盖被沙箱静默丢弃
  （此前只知道 `target/` 下 unlink 被拦），而新建文件拷贝成功，所以 `application.yml` 生效了、
  yaml 没有。规避：启动 classpath 把 `src/main/resources` 放在 `target/classes` 之前。
- **归一化顺序曾与类注释相反**：NFKC 写在"剥分隔符"之后，导致 `㈱` 这类兼容符号被当分隔符
  直接删除、NFKC 展开分支永远走不到。已调整为 ①NFKC 在前，并在类注释写明顺序不可换。

## 6. 回滚

- 全量回滚：`git revert` 本提交；DDL 侧 `DROP TABLE turbo_feed_1.sensitive_whitelist`
  （`sensitive_word` 表结构未变）。
- 只关检测（保留代码）：`turbofeed.content-security.enabled=false`，所有检测直接放行。
- 只关抗绕过（怀疑误杀来自归一化）：`normalize.strip-separators=false`。

## 7. 遗留

1. **图片视觉审核仍缺失** —— 文本拦得再严也管不住图，需机器初审（云 API / 本地视觉模型）。
2. `media.review.banned-keywords` 的 4 个 demo 词仍写在默认配置（违反"demo 值不进仓库配置"
   约束），且因匹配对象名而永不命中 —— 建议删除或改为匹配文案，需单独确认。
3. 繁简映射仅覆盖常用字表（637 条），非完整 OpenCC；生僻繁体仍可绕过。
4. 多实例下词库/白名单"即时刷新"只作用于当前实例，最长滞后 30s（定时指纹兜底）。
5. `sensitive_word.uk_word` 仍是 `utf8mb4_0900_ai_ci`，修它影响存量数据，属行为变更，未动。
6. 计数是进程内累计（重启归零），尚未接入 micrometer / health 端点。
7. 白名单尚无 admin 管理端点（仅词库有 `AdminSensitiveWordController`）；`stats()` 亦未暴露。
