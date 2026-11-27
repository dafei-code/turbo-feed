-- outbox_event 物理分片（128 表，分片键 id(雪花)，hashMod128）。
-- P3 分片改造：分片键必须选 id —— 投递主链路 findById/claim/markSent/markFailed/reDeadLetter 全部只有 id，
-- 若按 aggregate_id 分片会让这 5 个 CAS 操作广播到 128 片（每投一条 4 次跨片 UPDATE）。
-- 偶数下标落 ds_0=turbo_feed_1、奇数 ds_1=turbo_feed_2（autoTables + HASH_MOD 推断）。
--
-- ⚠️ 前置：必须先跑 SENT 清理（OutboxSentCleaner，默认保留 7 天）把终态冷数据清掉，
--    只迁移非终态行（PENDING/SENDING/FAILED/DEAD）到分片表；迁移步骤见
--    docs/ops/outbox-sharding-rollout.md。逻辑表 outbox_event 在 SS 配置里从 !SINGLE 移到 autoTables。
--
-- 表结构与原单表 outbox_event 完全一致（列/索引逐字对齐），只是物理表名加 _0.._127 后缀。

CREATE TABLE `turbo_feed_1`.`outbox_event_0` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_0)';

CREATE TABLE `turbo_feed_2`.`outbox_event_1` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_1)';

CREATE TABLE `turbo_feed_1`.`outbox_event_2` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_2)';

CREATE TABLE `turbo_feed_2`.`outbox_event_3` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_3)';

CREATE TABLE `turbo_feed_1`.`outbox_event_4` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_4)';

CREATE TABLE `turbo_feed_2`.`outbox_event_5` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_5)';

CREATE TABLE `turbo_feed_1`.`outbox_event_6` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_6)';

CREATE TABLE `turbo_feed_2`.`outbox_event_7` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_7)';

CREATE TABLE `turbo_feed_1`.`outbox_event_8` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_8)';

CREATE TABLE `turbo_feed_2`.`outbox_event_9` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_9)';

CREATE TABLE `turbo_feed_1`.`outbox_event_10` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_10)';

CREATE TABLE `turbo_feed_2`.`outbox_event_11` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_11)';

CREATE TABLE `turbo_feed_1`.`outbox_event_12` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_12)';

CREATE TABLE `turbo_feed_2`.`outbox_event_13` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_13)';

CREATE TABLE `turbo_feed_1`.`outbox_event_14` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_14)';

CREATE TABLE `turbo_feed_2`.`outbox_event_15` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_15)';

CREATE TABLE `turbo_feed_1`.`outbox_event_16` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_16)';

CREATE TABLE `turbo_feed_2`.`outbox_event_17` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_17)';

CREATE TABLE `turbo_feed_1`.`outbox_event_18` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_18)';

CREATE TABLE `turbo_feed_2`.`outbox_event_19` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_19)';

CREATE TABLE `turbo_feed_1`.`outbox_event_20` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_20)';

CREATE TABLE `turbo_feed_2`.`outbox_event_21` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_21)';

CREATE TABLE `turbo_feed_1`.`outbox_event_22` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_22)';

CREATE TABLE `turbo_feed_2`.`outbox_event_23` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_23)';

CREATE TABLE `turbo_feed_1`.`outbox_event_24` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_24)';

CREATE TABLE `turbo_feed_2`.`outbox_event_25` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_25)';

CREATE TABLE `turbo_feed_1`.`outbox_event_26` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_26)';

CREATE TABLE `turbo_feed_2`.`outbox_event_27` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_27)';

CREATE TABLE `turbo_feed_1`.`outbox_event_28` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_28)';

CREATE TABLE `turbo_feed_2`.`outbox_event_29` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_29)';

CREATE TABLE `turbo_feed_1`.`outbox_event_30` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_30)';

CREATE TABLE `turbo_feed_2`.`outbox_event_31` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_31)';

CREATE TABLE `turbo_feed_1`.`outbox_event_32` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_32)';

CREATE TABLE `turbo_feed_2`.`outbox_event_33` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_33)';

CREATE TABLE `turbo_feed_1`.`outbox_event_34` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_34)';

CREATE TABLE `turbo_feed_2`.`outbox_event_35` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_35)';

CREATE TABLE `turbo_feed_1`.`outbox_event_36` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_36)';

CREATE TABLE `turbo_feed_2`.`outbox_event_37` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_37)';

CREATE TABLE `turbo_feed_1`.`outbox_event_38` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_38)';

CREATE TABLE `turbo_feed_2`.`outbox_event_39` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_39)';

CREATE TABLE `turbo_feed_1`.`outbox_event_40` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_40)';

CREATE TABLE `turbo_feed_2`.`outbox_event_41` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_41)';

CREATE TABLE `turbo_feed_1`.`outbox_event_42` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_42)';

CREATE TABLE `turbo_feed_2`.`outbox_event_43` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_43)';

CREATE TABLE `turbo_feed_1`.`outbox_event_44` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_44)';

CREATE TABLE `turbo_feed_2`.`outbox_event_45` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_45)';

CREATE TABLE `turbo_feed_1`.`outbox_event_46` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_46)';

CREATE TABLE `turbo_feed_2`.`outbox_event_47` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_47)';

CREATE TABLE `turbo_feed_1`.`outbox_event_48` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_48)';

CREATE TABLE `turbo_feed_2`.`outbox_event_49` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_49)';

CREATE TABLE `turbo_feed_1`.`outbox_event_50` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_50)';

CREATE TABLE `turbo_feed_2`.`outbox_event_51` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_51)';

CREATE TABLE `turbo_feed_1`.`outbox_event_52` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_52)';

CREATE TABLE `turbo_feed_2`.`outbox_event_53` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_53)';

CREATE TABLE `turbo_feed_1`.`outbox_event_54` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_54)';

CREATE TABLE `turbo_feed_2`.`outbox_event_55` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_55)';

CREATE TABLE `turbo_feed_1`.`outbox_event_56` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_56)';

CREATE TABLE `turbo_feed_2`.`outbox_event_57` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_57)';

CREATE TABLE `turbo_feed_1`.`outbox_event_58` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_58)';

CREATE TABLE `turbo_feed_2`.`outbox_event_59` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_59)';

CREATE TABLE `turbo_feed_1`.`outbox_event_60` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_60)';

CREATE TABLE `turbo_feed_2`.`outbox_event_61` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_61)';

CREATE TABLE `turbo_feed_1`.`outbox_event_62` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_62)';

CREATE TABLE `turbo_feed_2`.`outbox_event_63` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_63)';

CREATE TABLE `turbo_feed_1`.`outbox_event_64` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_64)';

CREATE TABLE `turbo_feed_2`.`outbox_event_65` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_65)';

CREATE TABLE `turbo_feed_1`.`outbox_event_66` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_66)';

CREATE TABLE `turbo_feed_2`.`outbox_event_67` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_67)';

CREATE TABLE `turbo_feed_1`.`outbox_event_68` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_68)';

CREATE TABLE `turbo_feed_2`.`outbox_event_69` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_69)';

CREATE TABLE `turbo_feed_1`.`outbox_event_70` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_70)';

CREATE TABLE `turbo_feed_2`.`outbox_event_71` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_71)';

CREATE TABLE `turbo_feed_1`.`outbox_event_72` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_72)';

CREATE TABLE `turbo_feed_2`.`outbox_event_73` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_73)';

CREATE TABLE `turbo_feed_1`.`outbox_event_74` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_74)';

CREATE TABLE `turbo_feed_2`.`outbox_event_75` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_75)';

CREATE TABLE `turbo_feed_1`.`outbox_event_76` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_76)';

CREATE TABLE `turbo_feed_2`.`outbox_event_77` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_77)';

CREATE TABLE `turbo_feed_1`.`outbox_event_78` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_78)';

CREATE TABLE `turbo_feed_2`.`outbox_event_79` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_79)';

CREATE TABLE `turbo_feed_1`.`outbox_event_80` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_80)';

CREATE TABLE `turbo_feed_2`.`outbox_event_81` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_81)';

CREATE TABLE `turbo_feed_1`.`outbox_event_82` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_82)';

CREATE TABLE `turbo_feed_2`.`outbox_event_83` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_83)';

CREATE TABLE `turbo_feed_1`.`outbox_event_84` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_84)';

CREATE TABLE `turbo_feed_2`.`outbox_event_85` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_85)';

CREATE TABLE `turbo_feed_1`.`outbox_event_86` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_86)';

CREATE TABLE `turbo_feed_2`.`outbox_event_87` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_87)';

CREATE TABLE `turbo_feed_1`.`outbox_event_88` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_88)';

CREATE TABLE `turbo_feed_2`.`outbox_event_89` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_89)';

CREATE TABLE `turbo_feed_1`.`outbox_event_90` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_90)';

CREATE TABLE `turbo_feed_2`.`outbox_event_91` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_91)';

CREATE TABLE `turbo_feed_1`.`outbox_event_92` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_92)';

CREATE TABLE `turbo_feed_2`.`outbox_event_93` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_93)';

CREATE TABLE `turbo_feed_1`.`outbox_event_94` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_94)';

CREATE TABLE `turbo_feed_2`.`outbox_event_95` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_95)';

CREATE TABLE `turbo_feed_1`.`outbox_event_96` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_96)';

CREATE TABLE `turbo_feed_2`.`outbox_event_97` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_97)';

CREATE TABLE `turbo_feed_1`.`outbox_event_98` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_98)';

CREATE TABLE `turbo_feed_2`.`outbox_event_99` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_99)';

CREATE TABLE `turbo_feed_1`.`outbox_event_100` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_100)';

CREATE TABLE `turbo_feed_2`.`outbox_event_101` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_101)';

CREATE TABLE `turbo_feed_1`.`outbox_event_102` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_102)';

CREATE TABLE `turbo_feed_2`.`outbox_event_103` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_103)';

CREATE TABLE `turbo_feed_1`.`outbox_event_104` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_104)';

CREATE TABLE `turbo_feed_2`.`outbox_event_105` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_105)';

CREATE TABLE `turbo_feed_1`.`outbox_event_106` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_106)';

CREATE TABLE `turbo_feed_2`.`outbox_event_107` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_107)';

CREATE TABLE `turbo_feed_1`.`outbox_event_108` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_108)';

CREATE TABLE `turbo_feed_2`.`outbox_event_109` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_109)';

CREATE TABLE `turbo_feed_1`.`outbox_event_110` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_110)';

CREATE TABLE `turbo_feed_2`.`outbox_event_111` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_111)';

CREATE TABLE `turbo_feed_1`.`outbox_event_112` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_112)';

CREATE TABLE `turbo_feed_2`.`outbox_event_113` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_113)';

CREATE TABLE `turbo_feed_1`.`outbox_event_114` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_114)';

CREATE TABLE `turbo_feed_2`.`outbox_event_115` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_115)';

CREATE TABLE `turbo_feed_1`.`outbox_event_116` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_116)';

CREATE TABLE `turbo_feed_2`.`outbox_event_117` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_117)';

CREATE TABLE `turbo_feed_1`.`outbox_event_118` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_118)';

CREATE TABLE `turbo_feed_2`.`outbox_event_119` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_119)';

CREATE TABLE `turbo_feed_1`.`outbox_event_120` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_120)';

CREATE TABLE `turbo_feed_2`.`outbox_event_121` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_121)';

CREATE TABLE `turbo_feed_1`.`outbox_event_122` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_122)';

CREATE TABLE `turbo_feed_2`.`outbox_event_123` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_123)';

CREATE TABLE `turbo_feed_1`.`outbox_event_124` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_124)';

CREATE TABLE `turbo_feed_2`.`outbox_event_125` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_125)';

CREATE TABLE `turbo_feed_1`.`outbox_event_126` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_0.outbox_event_126)';

CREATE TABLE `turbo_feed_2`.`outbox_event_127` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 分片键+投递顺序键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(物理分片 ds_1.outbox_event_127)';

