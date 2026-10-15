-- =============================================================================
-- P0-3 Flyway 基线迁移：版本化「单表」（ShardingSphere !SINGLE 规则登记的 5 张）
-- -----------------------------------------------------------------------------
-- 设计要点：
--   · 分片表(540 张：user/media/.../user_fans) 仍由 deploy/mysql/init-local.sql 模板生成，
--     不纳入 Flyway（ShardingSphere + Flyway 对分片 DDL 协作成本高，评审方案已确认）。
--   · 本迁移只管「单表」(sensitive_word / sensitive_whitelist / user_phone_router /
--     outbox_event / review_task)，这些表落在物理库 ds_0(turbo_feed_1)。
--   · Flyway 配置(spring.flyway.url)直连物理库 ds_0（turbo_feed_1），**绕过 ShardingSphere 驱动**，
--     否则 DDL 会被 SS 路由/拒绝。DDL 使用「当前 schema」（不硬编码库名），由 spring.flyway.url
--     决定落到哪个物理库——改 url 即改落库，迁移脚本保持可移植。
--   · 全部 CREATE TABLE IF NOT EXISTS：现有库(已跑过 init-local.sql)幂等无操作；
--     全新库也能建出这 5 张表，满足「克隆即跑 + 版本化」双目标。
--   · DDL 与 init-local.sql 逐字一致，避免漂移。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `sensitive_word` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       VARCHAR(64)  NOT NULL                COMMENT '敏感词',
  `category`   VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT' COMMENT '分类: POLITICS/PORN/VIOLENCE/AD/DEFAULT',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=禁用 1=启用',
  `revision`   BIGINT       NOT NULL DEFAULT 1      COMMENT '修订号, 每次写操作 +1 用于变更检测',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_revision` (`revision`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库(单表, AC 自动机加载源)';

CREATE TABLE IF NOT EXISTS `sensitive_whitelist` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       VARCHAR(64)  NOT NULL                COMMENT '被豁免的词（与词库存储形态一致）',
  `scene`      VARCHAR(16)  NOT NULL DEFAULT '*'    COMMENT '生效场景: * / NICKNAME / CAPTION / COMMENT',
  `scope`      VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '生效范围: GLOBAL / USER',
  `owner_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT 'scope=USER 时的用户 ID；GLOBAL 恒为 0',
  `reason`     VARCHAR(255)          DEFAULT NULL   COMMENT '豁免原因（运营留痕）',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=禁用 1=启用',
  `revision`   BIGINT       NOT NULL DEFAULT 1      COMMENT '修订号, 每次写操作 +1',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_scene_scope` (`word`, `scene`, `scope`, `owner_id`),
  KEY `idx_word` (`word`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='误杀豁免白名单(单表, 内存索引加载源)';

CREATE TABLE IF NOT EXISTS `user_phone_router` (
  `phone`      VARCHAR(20) NOT NULL                COMMENT '登录手机号, 全局唯一(本表是唯一真正 enforce 该约束的地方)',
  `user_id`    BIGINT      NOT NULL                COMMENT '用户ID(user表分片键), 命中后按 id 精准查 user 单分片',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  PRIMARY KEY (`phone`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机号→UID 路由表(单表, 消灭跨分片广播查重)';

CREATE TABLE IF NOT EXISTS `outbox_event` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 投递顺序键',
  `event_type`      VARCHAR(64)  NOT NULL                COMMENT 'TIMELINE_APPEND / TIMELINE_REMOVE',
  `aggregate_id`    VARCHAR(255) NOT NULL                COMMENT '业务聚合标识(mediaId/postId), 对账与人工补偿定位用',
  `user_id`         BIGINT       NULL                    COMMENT '关联账号(便于按人排查; 非分片键)',
  `payload`         JSON         NOT NULL                COMMENT '事件体(投递所需全部信息, 回查库即可重放)',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED/DEAD',
  `attempt_count`   INT          NOT NULL DEFAULT 0      COMMENT '已尝试次数(领取时 +1)',
  `max_attempts`    INT          NOT NULL DEFAULT 5      COMMENT '重试上限, 达上限置 DEAD 等人工',
  `dead_count`      INT          NOT NULL DEFAULT 0      COMMENT '死信重投次数(达上限后永久终态, 由 OutboxDeadLetterSweeper 重投)',
  `last_error`      VARCHAR(512)          DEFAULT NULL   COMMENT '最近一次失败原因(截断)',
  `next_attempt_at` DATETIME     NOT NULL                COMMENT '下次可被领取的时点(退避)',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dispatch` (`status`, `next_attempt_at`),
  KEY `idx_reap` (`status`, `updated_at`),
  KEY `idx_aggregate` (`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(单表, 杜绝"事务已提交但消息丢失")';

CREATE TABLE IF NOT EXISTS `review_task` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID(自增, 单表队列)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '被举报内容标识(帖代表媒体ID)',
  `author_id`     BIGINT       NOT NULL                COMMENT '内容作者 userId',
  `task_type`     VARCHAR(32)  NOT NULL                COMMENT '触发类型: REPORT_ACCUMULATED',
  `trigger_count` INT          NOT NULL DEFAULT 0      COMMENT '触发时的待处理举报数',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待复审 1=无违规(维持发布) 2=确认违规(已下架)',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建单时间',
  `resolved_at`   DATETIME     NULL                    COMMENT '处置时间',
  `resolver`      VARCHAR(64)  NULL                    COMMENT '处置人(REVIEWER/ADMIN 的 userId)',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_media` (`media_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报累计复审任务队列(单表 ds_0, 抖音式复审闭环)';
