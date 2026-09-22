# 0048 演示代码清理：数据源口令/演示凭据全部退出仓库

> 起因：commit `1f3f781` 把本地演示口令（`password: "123456"`、`minioadmin`）提交进了公开仓库
> （截图指认）。全项目扫描后，本批次把「被 git 跟踪的演示凭据」全部改为加载期注入的占位符，
> 并顺带清理登录页预填演示账号与过时注释。

## 机制实证（先取证后动手）

ShardingSphere 5.5.3 官方注入通道，字节码级确认：

- 枚举 `URLArgumentPlaceholderType`：`NONE` / `ENVIRONMENT` / `SYSTEM_PROPS`；
- 驱动 URL 查询参数 `placeholder-type` 选择取值来源（`URLArgumentPlaceholderTypeFactory.valueOf(queryProps)`，缺省 `NONE`）；
- 配置 YAML 内占位符语法 `$${名称::默认值}`（正则 `\$\$\{(.*?)::(.*?)}`，`URLArgumentLine`）；
- 渲染入口 `ShardingSphereURLLoadEngine.loadContent()`：classpath 配置按行渲染后再交给 YamlEngine；
- 取值：`ENVIRONMENT → System.getenv(name)`、`SYSTEM_PROPS → System.getProperty(name)`、
  `NONE → null`；取值为空回落占位符内默认值，再为空则空串。

（注意：5.5.3 **没有**独立的 env-provider 类；占位符渲染只在驱动 URL 携带 `placeholder-type`
时才从环境/系统属性取值，仅写占位符不写 URL 参数等于没配。）

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-gateway/src/main/resources/application.yml` | 驱动 URL 追加 `?placeholder-type=ENVIRONMENT` |
| `tf-gateway/src/main/resources/shardingsphere-config.yaml` | ds_0/ds_1 三要素占位符化：URL ← `TURBOFEED_DB_0_URL` / `TURBOFEED_DB_1_URL`（默认本机库）、用户名 ← `TURBOFEED_DB_USERNAME`（默认 root）、口令 ← `TURBOFEED_DB_PASSWORD`（**无默认值**） |
| `tf-gateway/src/main/resources/shardingsphere-config-128.yaml` | 同上（128 扩容配置同构对齐，仍未启用） |
| `deploy/redis/docker-compose-cluster.yml` | `${REDIS_PASSWORD:-123456}` ×6 → `${REDIS_PASSWORD:?...}`（未 export 直接报错退出，fail-fast） |
| `turbo-feed-ui/login.html` | 删除登录表单预填的演示账号/密码 |
| `tf-gateway/src/main/resources/db/media_schema.sql` | 修正过时注释（"password 当前为空" → 占位符化说明） |
| `docs/ops/deployment.md` | 必需环境变量表新增 `TURBOFEED_DB_PASSWORD` 族 |

## 本机开发适配（重要）

- `shardingsphere-config.yaml` 由 SS 驱动独立加载，**gitignore 的本机 application.yml 对其无效**，
  口令只能走环境变量：`export TURBOFEED_DB_PASSWORD=123456`（IDEA 从终端启动可直接继承；
  Dock 启动需在运行配置 Environment variables 填一次，或 `launchctl setenv`）。
- 占位符默认值刻意保留本机库 URL 与 `root` 用户名（非机密），克隆后配一个口令变量即可跑；
  **任何真实口令不再进仓库**。

## 遗留与警示

- ⚠️ **git 历史泄漏**：真实口令 `removed_leaked_password` 曾存在于 commit `1f3f781` 及其父历史并已推送公开仓库，
  本次整改只清了工作区与未来提交，**历史 blob 仍可回溯**。需另行决定：抹历史（git-filter-repo + force push，
  破坏性操作）+ 立即修改该 MySQL 口令（泄露即视为已作废）。
- `deploy/mysql/init-local.sql` 的演示种子账号（123456）保留：文件本身定位就是本地演示库初始化，
  头部已声明「生产环境务必移除」，不属于泄漏。
- UI 端 `feed.html` 的分享/举报/下载 toast 仍为占位交互（功能未实现），属产品范围不属凭据泄漏，待排期。
