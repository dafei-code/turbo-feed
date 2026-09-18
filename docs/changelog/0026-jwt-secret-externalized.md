# 0026 JWT 签名密钥外部化：缺失即启动失败，杜绝静默弱密钥

- 日期：2026-09-17
- 范围：`tf-gateway`（安全配置）
- 关联：内容安全骨架 [#0028](0028-content-security-foundation.md)、[#0027 新号观察期](0027-new-user-watch-period.md)

## 1. 动机

原 `JwtUtil` 的 HS256 密钥以字面量/弱默认驻留代码与配置，违反安全底线：

- HS256 是对称算法，**持有密钥即可签发任意 userId 与任意 role 的令牌**；
- 一旦存在一个写死的兜底值，生产漏配就退化为「全站使用公开密钥」，等价于鉴权整体失效；
- 原实现仅在使用时才暴露问题（运行期 `NullPointerException` / 静默弱密钥），错误被发现得太晚。

正确做法：**缺失或强度不足一律在启动阶段失败**，把部署错误挡在部署阶段（参考
`SnowflakeConfig#requireInstanceId` 的取舍）。

## 2. 改动清单

| 文件 | 职责 |
|---|---|
| `config/JwtProperties.java`（新增） | `turbofeed.jwt.*` 配置绑定；`secret` **无默认值**；`requireSecret()` 缺失/空白/长度 `< 32` 抛 `IllegalStateException` |
| `security/JwtUtil.java`（修改） | 构造器注入 `JwtProperties`，**构造即调用 `requireSecret()`**；`hmac()` 改读 `properties.getSecret()` |

## 3. 关键设计

- **两道防线缺一不可**：`application.yml` 用 `${TURBOFEED_JWT_SECRET:}` 占位符（不出现字面量），
  `JwtProperties.secret` 字段也**不设默认值**——只外部化 yml 不够，占位符缺失会回落到字段初始值，
  故字段同样禁止兜底。
- **构造即校验**：`JwtUtil` 是单例 bean，其构造失败发生在 **context refresh 阶段** → 效果即
  「应用拒绝启动」，而非等到第一次签发/验签。不放在 `@PostConstruct`：避免与 `@ConfigurationProperties`
  绑定次序耦合，构造器里属性必然已绑定完成。
- **弱密钥拦截**：只查「非空」挡不住 `123456` 这类短密钥；HS256 强度取决于密钥熵量，故强制
  `≥ 32` 字符（UTF-8 下 ≥ 256 bit），不足即启动失败。
- **本地注入方式**：环境变量 `TURBOFEED_JWT_SECRET`（高熵随机串，如 `openssl rand -base64 32`）；
  生产经密钥管理注入，绝不进仓库。

## 4. 验证

- 未配置 `TURBOFEED_JWT_SECRET` 启动 → 日志报「缺少 JWT 签名密钥」并退出，符合预期；
- 配置长度 16 的弱密钥 → 报「强度不足」并退出；
- 配置 `≥ 32` 字符 → 正常签发/验签，`MessageDigest.isEqual` 恒定时间比较。

## 5. 遗留 / 后续

- 刷新令牌（refresh token）、密钥轮转、接入专业 JWT 库（jjwt / java-jwt）留待后续；
- 当前仍为演示级实现，生产前需补密钥管理与吊销能力。
