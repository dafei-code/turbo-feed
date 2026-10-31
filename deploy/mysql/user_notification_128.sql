-- user_notification 物理分片（128 表，分片键 user_id，hashMod128）。
-- 偶数下标落 ds_0=turbo_feed_1、奇数 ds_1=turbo_feed_2。
-- 写模型(OLTP)：每条通知一行；读模型 Redis 收件箱 ZSET tf:notify:{userId} + 未读计数 tf:notify:unread:{userId}。
-- 配置见 shardingsphere-config(-128).yaml 的 user_notification autoTable（shardingAlgorithmName: hashMod128）。

CREATE TABLE `turbo_feed_1`.`user_notification_0` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_0)';

CREATE TABLE `turbo_feed_2`.`user_notification_1` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_1)';

CREATE TABLE `turbo_feed_1`.`user_notification_2` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_2)';

CREATE TABLE `turbo_feed_2`.`user_notification_3` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_3)';

CREATE TABLE `turbo_feed_1`.`user_notification_4` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_4)';

CREATE TABLE `turbo_feed_2`.`user_notification_5` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_5)';

CREATE TABLE `turbo_feed_1`.`user_notification_6` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_6)';

CREATE TABLE `turbo_feed_2`.`user_notification_7` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_7)';

CREATE TABLE `turbo_feed_1`.`user_notification_8` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_8)';

CREATE TABLE `turbo_feed_2`.`user_notification_9` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_9)';

CREATE TABLE `turbo_feed_1`.`user_notification_10` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_10)';

CREATE TABLE `turbo_feed_2`.`user_notification_11` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_11)';

CREATE TABLE `turbo_feed_1`.`user_notification_12` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_12)';

CREATE TABLE `turbo_feed_2`.`user_notification_13` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_13)';

CREATE TABLE `turbo_feed_1`.`user_notification_14` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_14)';

CREATE TABLE `turbo_feed_2`.`user_notification_15` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_15)';

CREATE TABLE `turbo_feed_1`.`user_notification_16` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_16)';

CREATE TABLE `turbo_feed_2`.`user_notification_17` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_17)';

CREATE TABLE `turbo_feed_1`.`user_notification_18` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_18)';

CREATE TABLE `turbo_feed_2`.`user_notification_19` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_19)';

CREATE TABLE `turbo_feed_1`.`user_notification_20` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_20)';

CREATE TABLE `turbo_feed_2`.`user_notification_21` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_21)';

CREATE TABLE `turbo_feed_1`.`user_notification_22` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_22)';

CREATE TABLE `turbo_feed_2`.`user_notification_23` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_23)';

CREATE TABLE `turbo_feed_1`.`user_notification_24` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_24)';

CREATE TABLE `turbo_feed_2`.`user_notification_25` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_25)';

CREATE TABLE `turbo_feed_1`.`user_notification_26` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_26)';

CREATE TABLE `turbo_feed_2`.`user_notification_27` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_27)';

CREATE TABLE `turbo_feed_1`.`user_notification_28` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_28)';

CREATE TABLE `turbo_feed_2`.`user_notification_29` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_29)';

CREATE TABLE `turbo_feed_1`.`user_notification_30` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_30)';

CREATE TABLE `turbo_feed_2`.`user_notification_31` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_31)';

CREATE TABLE `turbo_feed_1`.`user_notification_32` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_32)';

CREATE TABLE `turbo_feed_2`.`user_notification_33` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_33)';

CREATE TABLE `turbo_feed_1`.`user_notification_34` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_34)';

CREATE TABLE `turbo_feed_2`.`user_notification_35` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_35)';

CREATE TABLE `turbo_feed_1`.`user_notification_36` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_36)';

CREATE TABLE `turbo_feed_2`.`user_notification_37` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_37)';

CREATE TABLE `turbo_feed_1`.`user_notification_38` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_38)';

CREATE TABLE `turbo_feed_2`.`user_notification_39` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_39)';

CREATE TABLE `turbo_feed_1`.`user_notification_40` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_40)';

CREATE TABLE `turbo_feed_2`.`user_notification_41` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_41)';

CREATE TABLE `turbo_feed_1`.`user_notification_42` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_42)';

CREATE TABLE `turbo_feed_2`.`user_notification_43` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_43)';

CREATE TABLE `turbo_feed_1`.`user_notification_44` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_44)';

CREATE TABLE `turbo_feed_2`.`user_notification_45` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_45)';

CREATE TABLE `turbo_feed_1`.`user_notification_46` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_46)';

CREATE TABLE `turbo_feed_2`.`user_notification_47` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_47)';

CREATE TABLE `turbo_feed_1`.`user_notification_48` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_48)';

CREATE TABLE `turbo_feed_2`.`user_notification_49` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_49)';

CREATE TABLE `turbo_feed_1`.`user_notification_50` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_50)';

CREATE TABLE `turbo_feed_2`.`user_notification_51` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_51)';

CREATE TABLE `turbo_feed_1`.`user_notification_52` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_52)';

CREATE TABLE `turbo_feed_2`.`user_notification_53` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_53)';

CREATE TABLE `turbo_feed_1`.`user_notification_54` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_54)';

CREATE TABLE `turbo_feed_2`.`user_notification_55` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_55)';

CREATE TABLE `turbo_feed_1`.`user_notification_56` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_56)';

CREATE TABLE `turbo_feed_2`.`user_notification_57` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_57)';

CREATE TABLE `turbo_feed_1`.`user_notification_58` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_58)';

CREATE TABLE `turbo_feed_2`.`user_notification_59` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_59)';

CREATE TABLE `turbo_feed_1`.`user_notification_60` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_60)';

CREATE TABLE `turbo_feed_2`.`user_notification_61` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_61)';

CREATE TABLE `turbo_feed_1`.`user_notification_62` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_62)';

CREATE TABLE `turbo_feed_2`.`user_notification_63` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_63)';

CREATE TABLE `turbo_feed_1`.`user_notification_64` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_64)';

CREATE TABLE `turbo_feed_2`.`user_notification_65` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_65)';

CREATE TABLE `turbo_feed_1`.`user_notification_66` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_66)';

CREATE TABLE `turbo_feed_2`.`user_notification_67` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_67)';

CREATE TABLE `turbo_feed_1`.`user_notification_68` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_68)';

CREATE TABLE `turbo_feed_2`.`user_notification_69` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_69)';

CREATE TABLE `turbo_feed_1`.`user_notification_70` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_70)';

CREATE TABLE `turbo_feed_2`.`user_notification_71` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_71)';

CREATE TABLE `turbo_feed_1`.`user_notification_72` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_72)';

CREATE TABLE `turbo_feed_2`.`user_notification_73` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_73)';

CREATE TABLE `turbo_feed_1`.`user_notification_74` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_74)';

CREATE TABLE `turbo_feed_2`.`user_notification_75` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_75)';

CREATE TABLE `turbo_feed_1`.`user_notification_76` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_76)';

CREATE TABLE `turbo_feed_2`.`user_notification_77` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_77)';

CREATE TABLE `turbo_feed_1`.`user_notification_78` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_78)';

CREATE TABLE `turbo_feed_2`.`user_notification_79` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_79)';

CREATE TABLE `turbo_feed_1`.`user_notification_80` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_80)';

CREATE TABLE `turbo_feed_2`.`user_notification_81` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_81)';

CREATE TABLE `turbo_feed_1`.`user_notification_82` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_82)';

CREATE TABLE `turbo_feed_2`.`user_notification_83` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_83)';

CREATE TABLE `turbo_feed_1`.`user_notification_84` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_84)';

CREATE TABLE `turbo_feed_2`.`user_notification_85` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_85)';

CREATE TABLE `turbo_feed_1`.`user_notification_86` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_86)';

CREATE TABLE `turbo_feed_2`.`user_notification_87` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_87)';

CREATE TABLE `turbo_feed_1`.`user_notification_88` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_88)';

CREATE TABLE `turbo_feed_2`.`user_notification_89` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_89)';

CREATE TABLE `turbo_feed_1`.`user_notification_90` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_90)';

CREATE TABLE `turbo_feed_2`.`user_notification_91` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_91)';

CREATE TABLE `turbo_feed_1`.`user_notification_92` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_92)';

CREATE TABLE `turbo_feed_2`.`user_notification_93` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_93)';

CREATE TABLE `turbo_feed_1`.`user_notification_94` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_94)';

CREATE TABLE `turbo_feed_2`.`user_notification_95` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_95)';

CREATE TABLE `turbo_feed_1`.`user_notification_96` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_96)';

CREATE TABLE `turbo_feed_2`.`user_notification_97` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_97)';

CREATE TABLE `turbo_feed_1`.`user_notification_98` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_98)';

CREATE TABLE `turbo_feed_2`.`user_notification_99` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_99)';

CREATE TABLE `turbo_feed_1`.`user_notification_100` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_100)';

CREATE TABLE `turbo_feed_2`.`user_notification_101` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_101)';

CREATE TABLE `turbo_feed_1`.`user_notification_102` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_102)';

CREATE TABLE `turbo_feed_2`.`user_notification_103` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_103)';

CREATE TABLE `turbo_feed_1`.`user_notification_104` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_104)';

CREATE TABLE `turbo_feed_2`.`user_notification_105` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_105)';

CREATE TABLE `turbo_feed_1`.`user_notification_106` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_106)';

CREATE TABLE `turbo_feed_2`.`user_notification_107` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_107)';

CREATE TABLE `turbo_feed_1`.`user_notification_108` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_108)';

CREATE TABLE `turbo_feed_2`.`user_notification_109` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_109)';

CREATE TABLE `turbo_feed_1`.`user_notification_110` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_110)';

CREATE TABLE `turbo_feed_2`.`user_notification_111` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_111)';

CREATE TABLE `turbo_feed_1`.`user_notification_112` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_112)';

CREATE TABLE `turbo_feed_2`.`user_notification_113` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_113)';

CREATE TABLE `turbo_feed_1`.`user_notification_114` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_114)';

CREATE TABLE `turbo_feed_2`.`user_notification_115` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_115)';

CREATE TABLE `turbo_feed_1`.`user_notification_116` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_116)';

CREATE TABLE `turbo_feed_2`.`user_notification_117` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_117)';

CREATE TABLE `turbo_feed_1`.`user_notification_118` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_118)';

CREATE TABLE `turbo_feed_2`.`user_notification_119` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_119)';

CREATE TABLE `turbo_feed_1`.`user_notification_120` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_120)';

CREATE TABLE `turbo_feed_2`.`user_notification_121` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_121)';

CREATE TABLE `turbo_feed_1`.`user_notification_122` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_122)';

CREATE TABLE `turbo_feed_2`.`user_notification_123` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_123)';

CREATE TABLE `turbo_feed_1`.`user_notification_124` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_124)';

CREATE TABLE `turbo_feed_2`.`user_notification_125` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_125)';

CREATE TABLE `turbo_feed_1`.`user_notification_126` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_1.user_notification_126)';

CREATE TABLE `turbo_feed_2`.`user_notification_127` (
  `id`          BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `user_id`     BIGINT       NOT NULL                COMMENT '接收方用户ID, 分片键',
  `type`        VARCHAR(32)  NOT NULL                COMMENT '通知类型 LIKE/COMMENT/REPLY/MENTION/FOLLOW/FAVORITE/SYSTEM/REVIEW_RESULT',
  `actor_id`    BIGINT       DEFAULT NULL            COMMENT '动作发起方用户ID(系统/审核结果可为空)',
  `target_type` VARCHAR(32)  DEFAULT NULL            COMMENT '目标类型 POST/COMMENT/USER/SYSTEM',
  `target_id`   VARCHAR(255) DEFAULT NULL            COMMENT '目标ID(帖子/评论/用户)',
  `summary`     VARCHAR(512) DEFAULT NULL            COMMENT '通知文案',
  `ref_payload` TEXT                                COMMENT '扩展JSON(可选)',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读/1已读',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `revision`    BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user_notification(物理分片 turbo_feed_2.user_notification_127)';

