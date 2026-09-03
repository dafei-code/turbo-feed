# 0001 · tf-gateway 与三大引擎解耦

- 日期：2026-08-28
- 类型：refactor(build)
- 关联方案：[服务拆分方案](../architecture/service-split.md)

## 动机

gateway 作为 HTTP 接入层不应以 Maven 依赖内嵌引擎模块：传递引入 Redisson / JDBC 导致进程内自动配置污染（此前已因此启动失败过一次，靠 `<exclusion>` 临时压制）。拆分后 gateway 成为纯接入单元，与引擎通过 HTTP / MQ 通信。

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-gateway/pom.xml` | 删除 `tf-hotspot` / `tf-feed-engine` / `tf-counter` 三个依赖及配套 redisson `<exclusions>`；description 同步更新 |

## 影响面

- gateway 只保留 `tf-shared` + web + sentinel + rocketmq + lombok，进程内不再出现 Redisson / JDBC；
- 此前的 redisson 传递依赖坑（启动连 127.0.0.1:6379 失败）从根源消除，不再需要 exclusion 兜底；
- gateway 现有业务（登录 / 媒体上传 / 审核事件）零代码改动（已验证 src 无引擎包引用）。

## 验证

- `grep -rn "com.turbofeed.(hotspot|counter|feedengine)" tf-gateway/src` → 0 匹配（改造前已确认）；
- `mvn -pl tf-gateway -am package` 后启动，无 Redisson 相关报错。

## 回滚

恢复 `tf-gateway/pom.xml` 中三个内部模块依赖（含 exclusions）即可，无代码层影响。
