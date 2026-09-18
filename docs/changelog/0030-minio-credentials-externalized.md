# 0030 MinIO 凭据外部化（P0-1）

- 日期：2026-09-17
- 范围：`tf-gateway`（`MinioStorageClient` / `MediaProperties` / `application.yml`）、`docs`
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一 · P0-1

## 1. 动机

仓库默认配置 `application.yml` 的 `turbofeed.media.minio.access-key/secret-key`
硬编码为 `minioadmin` 字面量，违反「敏感/demo 值不进仓库默认配置」约束，且一旦有人
把该默认配置误用于生产，对象存储将以默认口令裸奔。进一步的根因教训：**demo 默认值
常藏两处**（yml 字面量 + Java 字段 `= ""` 初始值），只清一处仍会在缺失环境变量时回落到空。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `MinioStorageClient.java` | 新增 `requireCredentials(MediaProperties.Minio)`：构建 `MinioClient` 前校验 `accessKey`/`secretKey` 非空，缺失即抛 `IllegalStateException`。该校验由 `afterPropertiesSet()`（Spring Context refresh 期）调用 → 缺失即**启动失败**，把事故拦在启动期。仅 `storage=minio` 激活时触发，`local` 存储不受影响 |
| `application.yml` | `access-key: minioadmin` → `access-key: ${TURBOFEED_MINIO_ACCESS_KEY:}`；`secret-key: minioadmin` → `secret-key: ${TURBOFEED_MINIO_SECRET_KEY:}`；注释更新为「禁止硬编码」 |
| `MediaProperties.java` | `Minio.accessKey` / `secretKey` 去掉 `= ""` 默认（改为无默认，缺失显式为 null，由 `requireCredentials` 拦下） |
| `docs/ops/deployment.md` | 生产前清单该项标记 `[x]`（已外部化） |
| `docs/modules/tf-gateway.md` | `turbofeed.media.minio.*` 说明改为「accessKey/secretKey 已外部化，仅 ENV 注入，缺失即启动失败」 |

## 3. 关键决策

- **fail-fast 优于 fail-open**：凭据缺失应在启动期暴露，而非等首次上传才 403/鉴权错。
- **两处默认一起清**：yaml 占位符空默认 + Java 字段去默认，确保「未注入环境变量即启动失败」，
  不留任何静默回落到空字符串的路径。
- **不影响 local 存储**：`@ConditionalOnProperty(storage=minio)` 之外 `MinioStorageClient` 不装配，
  默认 `storage=local` 克隆即跑的体验不变。

## 4. 验证

- 全局检索 `minioadmin`：源码 `application.yml` 已零残留（仅 `target/classes` 编译产物含旧字面量，不入库且沙箱覆盖 target 被静默丢弃）。
- 编译：`mvn -pl tf-gateway -am compile` 通过（exit 0）。
- 本地 `storage=minio` 时需导出 `TURBOFEED_MINIO_ACCESS_KEY` / `TURBOFEED_MINIO_SECRET_KEY`，
  否则启动抛 `IllegalStateException`（明确提示注入路径）。

## 5. 遗留

无。128 表分片等与凭据无关，不在本变更范围。
