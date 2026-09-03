# 0014 - 登录令牌 sub 改为携带 UID（对标抖音账号体系）

## 动机

turbo-feed 直接对标抖音架构。抖音账号体系的分层为：手机号（凭证层，仅登录入口用）→
UID（身份层，系统内部锚点、分片键、令牌载荷）→ sec_uid（对外脱敏层）。其单元化架构
官方文档明确"大部分业务以 UserID 作为分区维度"，即**分片键是 UID 而非手机号**。

0013 已将登录账号由 username 改为手机号，并建了不分片的 `user_phone_router(phone→uid)`
路由表，但 `AuthService` 签发 JWT 时仍把**手机号**写进 `sub`。这与抖音相反，且埋下隐患：
`user` 表分片键是 `id`（Long），一旦连表、业务层用 `UserContextHolder.requireUserId()`
按 id 分片查询，拿到的是手机号字符串 → 与 Long 主键对不上 → 路由错位 / 查不到。

本次把令牌载荷 `sub` 改为携带 **UID**，使登录链路与抖音完全一致：手机号只在登录入口
定位 UID，令牌与业务全程使用 UID。

## 改动清单

- `AuthProperties`：`demoUsers` 由 `Map<String,String>`（phone→password）改为
  `Map<String,DemoUser>`（phone→{password, uid}），新增嵌套类 `DemoUser`（含
  `password` 与 `uid` 两个字段及 getter/setter）；Javadoc 标注对标抖音的分层语义。
- `AuthService.login(phone, password)`：校验通过后由 `generateToken(phone)` 改为
  `generateToken(String.valueOf(account.getUid()))`；Javadoc 说明"sub 为 uid，非手机号"。
- `AuthController`：类注释同步"令牌 sub 携带 uid（身份层，对标抖音）"。
- `application.yml`：`turbofeed.auth.demo-users` 由 `13800138000: "123456"` 改为嵌套结构
  `"13800138000": { password: "123456", uid: 10001 }`；注释说明对标抖音（令牌装 uid、
  手机号仅凭证层）。
- `docs/architecture/sharding.md`：§5 跨分片唯一性改为 phone 口径（uk_phone /
  user_phone_router，替换原 username 描述）；新增 §8「账号体系对标抖音（设计原则）」分层表
  与核心约定；清理文件末尾混入的 `</content></invoke>` 垃圾标签。

## 影响面

- 登录契约变化：JWT `sub` 由手机号字符串变为数字 UID 字符串（demo 下为 `10001`）。
  `UserContextHolder` / `UserContext` 只透传该字符串，无需改动；下游业务"拿到的是 uid"
  反而更正确（与分片键 id 对齐）。
- 对外 API 形态不变（仍是 `/api/auth/login` 收 phone+password，返回 Bearer token）。
- 不新增第三方依赖、不改分片算法与 `user_schema.sql`。
- 旧令牌（sub=手机号）在下次登录前自然过期（exp 控制），无需强制失效。

## 验证

- 本机 `mvn -pl tf-gateway -am compile`：确认 `AuthProperties.DemoUser` 绑定与
  `AuthService` 调用编译通过。
- 启动后 `POST /api/auth/login?phone=13800138000&password=123456`，解码返回 JWT 的
  payload，`sub` 应为 `"10001"`（而非 `"13800138000"`）。
- 用该 token 调用任意需鉴权接口，`UserContextHolder.requireUserId()` 应返回 `"10001"`。
- 本地需 MySQL 且先建 `turbo_feed_1/2` 并执行 `user_schema.sql`（否则 SS 数据源起不来，
  与本次改动无关）。

## 回滚

- git 回退本 changelog 涉及的 4 个文件（AuthProperties / AuthService / AuthController /
  application.yml）+ sharding.md。
- 回滚后令牌 `sub` 重新为手机号，旧行为恢复；已签发的 UID 令牌在过期前仍可正常校验
  （`JwtUtil.parseUserId` 不关心 sub 是手机号还是 uid，只校验签名与 exp）。

## 复盘

- 分片键与令牌身份必须同源：路由用 id，令牌就该装 id。此前"令牌装手机号"是把凭证层标识
  误当成身份层标识，与抖音"凭证/身份分离"原则相悖，连表必错位——这次从设计上闭合。
- 第三方依赖/通用范式（抖音、SS 内置算法等）的接口与字段，须以官方文档/字节码为准，
  不能凭字面推测（延续 0011/0012 的教训）。
