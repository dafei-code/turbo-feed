# Changelog 0013 · 登录账号改为手机号，新增 user_phone_router 路由表

> 日期：2026-08-29 · 类型：Refactor（登录标识方案 + 建表结构）· 影响面：`tf-gateway` 登录链路 + `user` 建表 + 新增不分片路由表
> 关联：0010（分片用户表建表，本变更在此基础上把登录列从 username 收敛为 phone）

## 1. 动机 / 根因

原 `user` 表以 `username` 作登录名，但分片键是 `id`（雪花 Long）：

1. `uk_username` 唯一索引只在**单张物理表**内生效，ShardingSphere 不会跨分片去重——两个不同 `id` 的用户若注册相同 `username` 且落在不同分片，都能插入成功，跨分片出现重复 `username`（见 0010 文件头"分片唯一性坑"段）；
2. 业务上 `username` 还要兼顾展示语义，作登录标识并不自然；
3. 手机号天然全局唯一、用户记忆成本低，**用手机号当登录账号**可一举消除上述跨分片唯一性坑，且更贴合账号体系直觉。

因此本次：把登录列 `username` → `phone`；同时为"未来连 `user` 表时按 `phone` 查询"预留不分片路由表 `user_phone_router`，避免 `WHERE phone=?` 无分片键导致的全分片广播 + 保证 `phone` 全局唯一。

## 2. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `tf-gateway/.../controller/AuthController.java` | `login(@RequestParam String username, ...)` → `login(@RequestParam String phone, ...)`；类与方法 javadoc 改为手机号口径；调用 `authService.login(phone, password)` |
| 2 | `tf-gateway/.../service/AuthService.java` | `login(String username,...)` → `login(String phone,...)`；空值校验文案"用户名与密码"→"手机号与密码"；`getDemoUsers().get(username)` → `get(phone)`；失败日志 `username={}` → `phone={}`；`generateToken(username)` → `generateToken(phone)`；javadoc 同步 |
| 3 | `tf-gateway/.../config/AuthProperties.java` | 注释"用户名 -> 密码"→"手机号 -> 密码"；默认演示账号 `Map.of("admin","123456")` → `Map.of("13800138000","123456")` |
| 4 | `tf-gateway/src/main/resources/application.yml` | `turbofeed.auth.demo-users` 下 `admin: "123456"` → `13800138000: "123456"`；注释改为"键为手机号" |
| 5 | `tf-gateway/src/main/resources/db/user_schema.sql` | 文件头"跨分片唯一性坑"段改为 phone 口径；4 张物理表 `username VARCHAR(64)` → `phone VARCHAR(20) NOT NULL`，索引 `uk_username` → `uk_phone`；文末新增不分片 `user_phone_router` 表 DDL |
| 6 | `shardingsphere-config.yaml` | **不动**（里面的 `username: root` 是 DB 连接账号，非登录账号；本次未引入新数据源） |
| 7 | `docs/changelog/0013-login-by-phone.md` | 本文 |

> 注意：`UserContext` / `UserContextHolder` / `JwtUtil` **未改动**——它们只存/取 `userId` 字符串（取自 JWT `sub`），手机号作为 `sub` 内容对其完全透明，下游业务无感。

## 3. 影响面

- **登录契约变化**：`POST /api/auth/login` 入参由 `username` 改为 `phone`（前端需同步改参数名）；返回 JWT 的 `sub` 由用户名变为手机号串；
- **Demo 账号**：默认可用 `13800138000 / 123456` 登录（原 `admin/123456` 失效）；
- **建表**：执行 `user_schema.sql` 后，4 张物理表登录列名为 `phone`，唯一索引为 `uk_phone`；新增 `user_phone_router` 表（单库单表，不分片）；
- **路由表当前未接入**：demo 阶段登录走配置不查表，`user_phone_router` 仅为 DDL 预留；未来连 `user` 表时需把它挂到独立不分片数据源（SS `!SINGLE` 规则或直连 `ds_router`），并在注册 / 登录流程中先读写该表；
- **编译**：纯字符串重命名 + 建表 DDL，不涉及新依赖，`mvn -pl tf-gateway -am compile` 应一次过。

## 4. 验证

| 项 | 命令 / 现象 | 预期 |
|---|---|---|
| 编译 | `mvn -pl tf-gateway -am clean compile` | 0 报错；无 `username` 入参残留（grep `login(` 入参为 phone） |
| 登录（demo） | `curl -X POST /api/auth/login -d phone=13800138000 -d password=123456` | 返回 JWT；`sub` 字段为 `13800138000` |
| 失败登录 | 错密码 / 空手机号 | 抛 `UNAUTHORIZED` / `PARAM_ERROR`，日志 `登录失败: phone=...` |
| 建表校验 | 在 `turbo_feed_1/2` 各库执行 `user_schema.sql` | 4 张 `user_*` 含 `phone` + `uk_phone`；`user_phone_router` 建表成功 |
| 下游无感 | 任意依赖 `UserContextHolder.requireUserId()` 的接口（如上传） | 取到的字符串为手机号，行为不变 |

## 5. 回滚

1. 若手机号方案回退到 username：
   - 代码：`git revert` 本 changelog 涉及的 AuthController / AuthService / AuthProperties / application.yml 四个文件改动；
   - 建表：重新执行 0010 原 `user_schema.sql`（username 版），并 `DROP TABLE user_phone_router`；
2. 回滚后 JWT `sub` 恢复为用户名串，前端参数名同步回 `username`。

## 6. 教训 / 设计取舍

1. **改动前先全量扫引用，避免漏改**：本次 `grep username` 发现 `shardingsphere-config.yaml` 的 `username: root` 是 **DB 连接账号**，与登录账号同名但语义不同——若机械 replace_all 会破坏数据源配置。确认改动面后再动手；
2. **设计取舍由用户拍板，不擅自做主**：涉及"建表字段（username 改 phone / 保留并新增）"与"是否现在建路由表"两个抉择，用选项请用户确认（用户选"按推荐" + "现在建路由表"），符合项目"改动前确认"约定；
3. **跨分片唯一性前置解决**：路由表 `user_phone_router(phone_hash -> user_id + 分片号)` 同时解决"全局唯一去重"与"登录精准定位分片（免广播）"两个问题，比单纯依赖 `uk_phone` 更稳。
