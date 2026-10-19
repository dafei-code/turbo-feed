-- UserJdbcRepositoryIT 集成测 schema（Testcontainers MySQL 启动时加载）。
-- 生产环境 user 表走 ShardingSphere 分片（user_0..user_N），此处用单张 user 表
-- 验证仓库 SQL 与手机号路由闭环的正确性（物理分片改写由 ShardingSphere 自身测试覆盖）。

CREATE TABLE user (
    id            BIGINT       NOT NULL,
    phone         VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255),
    nickname      VARCHAR(128),
    status        INT,
    role          VARCHAR(32),
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_phone_router (
    phone   VARCHAR(64) NOT NULL,
    user_id BIGINT       NOT NULL,
    PRIMARY KEY (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
