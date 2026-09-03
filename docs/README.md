# turbo-feed 技术文档

> 本目录与代码同仓同构，是项目的**唯一技术文档来源**（Single Source of Truth）。
> 代码变更时必须同步更新对应文档，并按编号追加 [changelog](#5-变更记录)。

## 目录结构

```
docs/
├── README.md                 # 本文件：文档索引与维护约定
├── architecture/             # 架构层文档
│   ├── overview.md           # 系统总体架构：模块 / 通信 / 技术栈 / 关键决策
│   └── service-split.md      # 微服务拆分方案：部署单元 / 端口 / 通信矩阵
├── modules/                  # 模块级技术文档（与 Maven 模块一一对应）
│   ├── tf-gateway.md         # 网关：认证 / 媒体上传 / 审核 / 事件 / 配置
│   ├── tf-counter.md         # 计数服务：现状 + 设计蓝图
│   ├── tf-feed-engine.md     # Feed 引擎：现状 + 设计蓝图
│   ├── tf-hotspot.md         # 热点探测 SDK：设计蓝图
│   └── tf-shared.md          # 公共契约：Result / ErrorCode
├── api/                      # 接口文档
│   └── gateway-api.md        # HTTP 接口：统一返回结构 / 错误码 / curl 示例
├── ops/                      # 运维层文档
│   └── deployment.md         # 构建部署 / 配置开关 / 环境演进
└── changelog/                # 变更记录（每变更一份，编号递增）
```

## 推荐阅读路径

| 角色 | 路径 |
|---|---|
| 新接手工程师 | [架构总览](architecture/overview.md) → [tf-gateway](modules/tf-gateway.md) → [API](api/gateway-api.md) → 本地跑起来见 [部署](ops/deployment.md) |
| 前端 / 联调 | [API 文档](api/gateway-api.md)（含登录换取 JWT 与上传示例） |
| 运维 / 部署 | [部署文档](ops/deployment.md) → [服务拆分](architecture/service-split.md) |
| 查历史变更 | [changelog 目录](changelog/)（倒序看编号） |

## 文档维护约定

1. **代码与文档同步**：改了类职责 / 接口签名 / 配置键，同一提交内更新对应模块文档；
2. **每变更一份 changelog**：编号 `NNNN-主题.md`，记录动机 / 改动 / 影响 / 验证 / 回滚；
3. **文档只写"为什么"和"契约"，不复述代码**：能从代码直接读出的细节不重复，长决策记录写在文档里；
4. 模块文档与 Maven 模块一一对应，新增模块必须新增对应 `modules/*.md`。
