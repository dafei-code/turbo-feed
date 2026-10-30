# 0078 · P1 行为明细归档到 MinIO（采集链路落地）

- **状态**：已完成（编译 + 引擎 214 测试全绿，含 5 个归档单测；镜像内真启动 + MinIO 实测上传未做，本机 MinIO 9000 未监听）
- **优先级**：P1（采集链路，评审文档认定的「数据只在单机卷、磁盘故障即全失」闭环项）
- **关联**：0057（behavior-log M0 落盘）、0077（打开落盘开关）、0069（MinIO 私化）

## 为什么做

0077 把 behavior-log 开关打开了，但**打开只是第一步**：数据落在 `behavior-logs` 命名卷里，
仍是「单机单副本」。磁盘故障 / 卷所在盘损坏 → 全部训练样本归零。这跟评审文档里
「无采集链路」那条风险是同一件事。

P1 的目标：引擎侧每小时把已完成小时的 JSONL **压缩上传 MinIO**，拿到多副本 + 按 `dt/hh` 分区 +
可被离线任务直接读——把明细从「单机卷里的文本」变成「对象存储里的可回溯数据集」。

## 改动

### 1. 落盘按小时切文件（`BehaviorLogSink`）

| 项 | 前 | 后 |
| --- | --- | --- |
| 切分粒度 | 按天 `feed-behavior-YYYYMMDD.jsonl` | 按小时 `feed-behavior-YYYYMMDD-HH.jsonl` |

**为什么必须改**：归档要整文件拷贝/上传，按天切的文件在归档时刻仍在被后台写线程追加，
会截到半截。按小时切后，**当前小时之外的文件不可变**，归档只碰已完成小时，上传的是完整文件。
仅改了 `DAY`→`HOUR` 格式化与 `writerFor` 的 key，队列/异步/丢得起的语义完全不变。

### 2. 新增归档四件套（`tf-feed-engine/.../logging/`）

| 类 | 职责 |
| --- | --- |
| `BehaviorLogArchiveProperties` | `@ConfigurationProperties("turbofeed.feed.behavior-log.archive")`：enabled / minioEndpoint / minioAccessKey / minioSecretKey / minioRegion / bucket / baseKeyPrefix / localRetentionHours。默认全关、凭据空 |
| `BehaviorLogUploader`（接口） | 防腐层端口：`upload(Path gzFile, String objectKey)`。实现可换 MinIO / S3 / 本地 |
| `MinioBehaviorLogUploader` | MinIO / S3 兼容实现。双检锁懒初始化、`ensureBucket` 最佳努力自动建桶、任意异常包成 `IOException` 上抛供归档器重试；凭据全来自环境变量，无仓库字面量 |
| `BehaviorLogArchiver` | `@Scheduled(cron="0 5 * * * *")` 每小时跑；列目录→跳过当前小时/已归档→gzip→上传→写 `.archived` 标记→本地保留期清理。全 fail-open |

### 3. 配置与部署注入

| 位置 | 改动 |
| --- | --- |
| `tf-feed-engine/pom.xml` | 新增 `io.minio:minio:8.5.7`（与网关同版本）；`@ConditionalOnProperty(archive.enabled)` 保证默认不加载 |
| `tf-feed-engine/src/main/resources/application.yml` | `behavior-log` 下新增 `archive.*` 块，全部 `${TURBOFEED_BEHAVIOR_LOG_ARCHIVE_*:默认关}` |
| `deploy/compose/docker-compose.prod.yml` | engine 注入 `TURBOFEED_BEHAVIOR_LOG_ARCHIVE_*`：默认 `false`，端点用内部 `http://minio:9000`，access/secret 复用 `TURBOFEED_MINIO_*` |
| `deploy/env/.env.example` | 新增归档段（同样带 `BEHAVIOR_LOG` 前缀陷阱说明） |
| `deploy/scripts/preflight.sh` | 归档开启时校验 MinIO 端点 / accessKey / secretKey / 桶非空，缺失阻断启动 |

### 4. 文档同步

- `docs/architecture/behavior-data-pipeline.md`：§2 加「P1 归档」行 + 文件切分改按小时；
  §4 差距表「落盘」行由「无副本/无分区/未压缩」改为「已补齐多副本 + dt/hh 分区 + gzip」；
  §5 演进路径 P1 标记「已完成」；§6.5 新增归档配置与契约；§7 风险表「无采集链路 / 无清理策略」标记已缓解。

## 设计契约（务必记住，避免以后踩坑）

- **跳过当前小时**：当前小时文件仍在写，整文件不可变前不碰，保证上传完整。
- **幂等（`.archived` 标记）**：每个成功上传的本地文件写一个同名空标记，归档任务幂等——
  MinIO 抖动 / 重启后下个周期补传，不重传不丢。
- **fail-open**：单文件上传失败只告警不抛，本地文件保留、下个周期重试，不拖垮主流程。
- **本地保留期**：归档成功且文件小时早于 `now - localRetentionHours` 才清本地；**未归档成功的文件即使超期也保留**（数据安全优先）。
- **桶自动创建**：目标桶不存在时 `ensureBucket` 最佳努力建桶，无需在 `minio-init` 预建（与媒体桶 `turbo-feed-media` 解耦）。
- **变量名必须带 `BEHAVIOR_LOG`**：同 FEED 陷阱——compose 只转发带此前缀的名字进容器，不带的下个周期你会在桶里看到一条没有。

## 验证证据

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 引擎全量编译 + 测试 | `mvn -o -pl tf-feed-engine -am test` | **214 个测试全绿，0 失败**（含 5 个 `BehaviorLogArchiverTest`） |
| key 格式 / 内容 | `BehaviorLogArchiverTest.archivesOldHourWithCorrectKeyAndContent` | key=`behavior/dt=2026-01-01/hh=00/feed-behavior-20260101-00.jsonl.gz`，gzip 解压内容逐行一致 |
| 跳过当前小时 | `skipsCurrentHourButArchivesOld` | 当前小时文件不上传、无标记；旧文件正常归档 |
| 幂等去重 | `skipsAlreadyArchivedFile` | 已 `.archived` 文件 0 上传 |
| 失败重试 | `uploadFailureRetainsLocalAndRetriesNextRun` | 首次失败无标记/本地保留，二次成功写标记 |
| 本地保留期 | `retentionCleansArchivedOldFile` | 超过保留期的已归档文件本地 `.jsonl` + 标记一起清除 |

## 未做留白（诚实边界）

- **镜像内真启动 + MinIO 实测上传**：本机 MinIO 9000 未监听，未做端到端上传验证。
  代码路径已被单测全覆盖（FakeUploader 验证 key/内容/去重/重试/保留期），但**真实 `putObject` 链路需 Docker 起 MinIO 后跑一次确认**（建议接 0077 同套 compose 起 MinIO 后开 `ARCHIVE_ENABLED=true` 真启引擎）。
- **`dropped()` 未接 Prometheus**：队列满丢弃仍是内存计数，建议补 `tf_behavior_log_dropped_total`（属可观测性增强，非阻塞）。
- **P2 转 Parquet / P3 ClickHouse 看板**：未启动，仍是 JSONL（压缩）形态，量大时扫描效率弱于列式。
