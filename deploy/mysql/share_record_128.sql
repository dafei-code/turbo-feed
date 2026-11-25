-- share_record 物理分片（128 表，分片键 code，hashMod128）。
-- 偶数下标落 ds_0=turbo_feed_1、奇数下标落 ds_1=turbo_feed_2，与 comment/media_like 同策略。
-- 生成端按 code 点查单分片；「我的分享」按 sharer_id 查会广播 128 片（低频，见方案 §8.1）。
-- 配置见 shardingsphere-config-128.yaml 的 share_record autoTable。
-- uk_id 用雪花 ID 兜底唯一；uk_code(=PK) 是短码唯一性最后一道（方案 §4.1.1）。

CREATE TABLE `turbo_feed_1`.`share_record_0` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_0)';

CREATE TABLE `turbo_feed_2`.`share_record_1` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_1)';

CREATE TABLE `turbo_feed_1`.`share_record_2` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_2)';

CREATE TABLE `turbo_feed_2`.`share_record_3` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_3)';

CREATE TABLE `turbo_feed_1`.`share_record_4` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_4)';

CREATE TABLE `turbo_feed_2`.`share_record_5` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_5)';

CREATE TABLE `turbo_feed_1`.`share_record_6` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_6)';

CREATE TABLE `turbo_feed_2`.`share_record_7` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_7)';

CREATE TABLE `turbo_feed_1`.`share_record_8` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_8)';

CREATE TABLE `turbo_feed_2`.`share_record_9` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_9)';

CREATE TABLE `turbo_feed_1`.`share_record_10` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_10)';

CREATE TABLE `turbo_feed_2`.`share_record_11` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_11)';

CREATE TABLE `turbo_feed_1`.`share_record_12` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_12)';

CREATE TABLE `turbo_feed_2`.`share_record_13` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_13)';

CREATE TABLE `turbo_feed_1`.`share_record_14` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_14)';

CREATE TABLE `turbo_feed_2`.`share_record_15` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_15)';

CREATE TABLE `turbo_feed_1`.`share_record_16` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_16)';

CREATE TABLE `turbo_feed_2`.`share_record_17` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_17)';

CREATE TABLE `turbo_feed_1`.`share_record_18` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_18)';

CREATE TABLE `turbo_feed_2`.`share_record_19` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_19)';

CREATE TABLE `turbo_feed_1`.`share_record_20` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_20)';

CREATE TABLE `turbo_feed_2`.`share_record_21` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_21)';

CREATE TABLE `turbo_feed_1`.`share_record_22` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_22)';

CREATE TABLE `turbo_feed_2`.`share_record_23` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_23)';

CREATE TABLE `turbo_feed_1`.`share_record_24` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_24)';

CREATE TABLE `turbo_feed_2`.`share_record_25` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_25)';

CREATE TABLE `turbo_feed_1`.`share_record_26` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_26)';

CREATE TABLE `turbo_feed_2`.`share_record_27` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_27)';

CREATE TABLE `turbo_feed_1`.`share_record_28` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_28)';

CREATE TABLE `turbo_feed_2`.`share_record_29` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_29)';

CREATE TABLE `turbo_feed_1`.`share_record_30` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_30)';

CREATE TABLE `turbo_feed_2`.`share_record_31` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_31)';

CREATE TABLE `turbo_feed_1`.`share_record_32` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_32)';

CREATE TABLE `turbo_feed_2`.`share_record_33` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_33)';

CREATE TABLE `turbo_feed_1`.`share_record_34` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_34)';

CREATE TABLE `turbo_feed_2`.`share_record_35` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_35)';

CREATE TABLE `turbo_feed_1`.`share_record_36` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_36)';

CREATE TABLE `turbo_feed_2`.`share_record_37` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_37)';

CREATE TABLE `turbo_feed_1`.`share_record_38` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_38)';

CREATE TABLE `turbo_feed_2`.`share_record_39` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_39)';

CREATE TABLE `turbo_feed_1`.`share_record_40` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_40)';

CREATE TABLE `turbo_feed_2`.`share_record_41` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_41)';

CREATE TABLE `turbo_feed_1`.`share_record_42` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_42)';

CREATE TABLE `turbo_feed_2`.`share_record_43` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_43)';

CREATE TABLE `turbo_feed_1`.`share_record_44` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_44)';

CREATE TABLE `turbo_feed_2`.`share_record_45` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_45)';

CREATE TABLE `turbo_feed_1`.`share_record_46` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_46)';

CREATE TABLE `turbo_feed_2`.`share_record_47` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_47)';

CREATE TABLE `turbo_feed_1`.`share_record_48` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_48)';

CREATE TABLE `turbo_feed_2`.`share_record_49` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_49)';

CREATE TABLE `turbo_feed_1`.`share_record_50` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_50)';

CREATE TABLE `turbo_feed_2`.`share_record_51` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_51)';

CREATE TABLE `turbo_feed_1`.`share_record_52` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_52)';

CREATE TABLE `turbo_feed_2`.`share_record_53` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_53)';

CREATE TABLE `turbo_feed_1`.`share_record_54` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_54)';

CREATE TABLE `turbo_feed_2`.`share_record_55` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_55)';

CREATE TABLE `turbo_feed_1`.`share_record_56` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_56)';

CREATE TABLE `turbo_feed_2`.`share_record_57` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_57)';

CREATE TABLE `turbo_feed_1`.`share_record_58` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_58)';

CREATE TABLE `turbo_feed_2`.`share_record_59` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_59)';

CREATE TABLE `turbo_feed_1`.`share_record_60` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_60)';

CREATE TABLE `turbo_feed_2`.`share_record_61` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_61)';

CREATE TABLE `turbo_feed_1`.`share_record_62` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_62)';

CREATE TABLE `turbo_feed_2`.`share_record_63` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_63)';

CREATE TABLE `turbo_feed_1`.`share_record_64` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_64)';

CREATE TABLE `turbo_feed_2`.`share_record_65` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_65)';

CREATE TABLE `turbo_feed_1`.`share_record_66` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_66)';

CREATE TABLE `turbo_feed_2`.`share_record_67` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_67)';

CREATE TABLE `turbo_feed_1`.`share_record_68` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_68)';

CREATE TABLE `turbo_feed_2`.`share_record_69` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_69)';

CREATE TABLE `turbo_feed_1`.`share_record_70` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_70)';

CREATE TABLE `turbo_feed_2`.`share_record_71` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_71)';

CREATE TABLE `turbo_feed_1`.`share_record_72` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_72)';

CREATE TABLE `turbo_feed_2`.`share_record_73` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_73)';

CREATE TABLE `turbo_feed_1`.`share_record_74` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_74)';

CREATE TABLE `turbo_feed_2`.`share_record_75` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_75)';

CREATE TABLE `turbo_feed_1`.`share_record_76` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_76)';

CREATE TABLE `turbo_feed_2`.`share_record_77` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_77)';

CREATE TABLE `turbo_feed_1`.`share_record_78` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_78)';

CREATE TABLE `turbo_feed_2`.`share_record_79` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_79)';

CREATE TABLE `turbo_feed_1`.`share_record_80` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_80)';

CREATE TABLE `turbo_feed_2`.`share_record_81` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_81)';

CREATE TABLE `turbo_feed_1`.`share_record_82` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_82)';

CREATE TABLE `turbo_feed_2`.`share_record_83` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_83)';

CREATE TABLE `turbo_feed_1`.`share_record_84` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_84)';

CREATE TABLE `turbo_feed_2`.`share_record_85` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_85)';

CREATE TABLE `turbo_feed_1`.`share_record_86` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_86)';

CREATE TABLE `turbo_feed_2`.`share_record_87` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_87)';

CREATE TABLE `turbo_feed_1`.`share_record_88` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_88)';

CREATE TABLE `turbo_feed_2`.`share_record_89` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_89)';

CREATE TABLE `turbo_feed_1`.`share_record_90` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_90)';

CREATE TABLE `turbo_feed_2`.`share_record_91` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_91)';

CREATE TABLE `turbo_feed_1`.`share_record_92` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_92)';

CREATE TABLE `turbo_feed_2`.`share_record_93` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_93)';

CREATE TABLE `turbo_feed_1`.`share_record_94` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_94)';

CREATE TABLE `turbo_feed_2`.`share_record_95` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_95)';

CREATE TABLE `turbo_feed_1`.`share_record_96` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_96)';

CREATE TABLE `turbo_feed_2`.`share_record_97` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_97)';

CREATE TABLE `turbo_feed_1`.`share_record_98` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_98)';

CREATE TABLE `turbo_feed_2`.`share_record_99` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_99)';

CREATE TABLE `turbo_feed_1`.`share_record_100` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_100)';

CREATE TABLE `turbo_feed_2`.`share_record_101` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_101)';

CREATE TABLE `turbo_feed_1`.`share_record_102` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_102)';

CREATE TABLE `turbo_feed_2`.`share_record_103` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_103)';

CREATE TABLE `turbo_feed_1`.`share_record_104` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_104)';

CREATE TABLE `turbo_feed_2`.`share_record_105` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_105)';

CREATE TABLE `turbo_feed_1`.`share_record_106` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_106)';

CREATE TABLE `turbo_feed_2`.`share_record_107` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_107)';

CREATE TABLE `turbo_feed_1`.`share_record_108` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_108)';

CREATE TABLE `turbo_feed_2`.`share_record_109` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_109)';

CREATE TABLE `turbo_feed_1`.`share_record_110` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_110)';

CREATE TABLE `turbo_feed_2`.`share_record_111` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_111)';

CREATE TABLE `turbo_feed_1`.`share_record_112` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_112)';

CREATE TABLE `turbo_feed_2`.`share_record_113` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_113)';

CREATE TABLE `turbo_feed_1`.`share_record_114` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_114)';

CREATE TABLE `turbo_feed_2`.`share_record_115` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_115)';

CREATE TABLE `turbo_feed_1`.`share_record_116` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_116)';

CREATE TABLE `turbo_feed_2`.`share_record_117` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_117)';

CREATE TABLE `turbo_feed_1`.`share_record_118` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_118)';

CREATE TABLE `turbo_feed_2`.`share_record_119` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_119)';

CREATE TABLE `turbo_feed_1`.`share_record_120` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_120)';

CREATE TABLE `turbo_feed_2`.`share_record_121` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_121)';

CREATE TABLE `turbo_feed_1`.`share_record_122` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_122)';

CREATE TABLE `turbo_feed_2`.`share_record_123` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_123)';

CREATE TABLE `turbo_feed_1`.`share_record_124` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_124)';

CREATE TABLE `turbo_feed_2`.`share_record_125` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_125)';

CREATE TABLE `turbo_feed_1`.`share_record_126` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_1.share_record_126)';

CREATE TABLE `turbo_feed_2`.`share_record_127` (
  `code`          VARCHAR(16)  NOT NULL                COMMENT '短链码(分片键, 定长11位Base62)',
  `id`            BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `post_id`       VARCHAR(255) NOT NULL                COMMENT '内容身份(=timelineKey)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '媒资ID(状态校验用)',
  `owner_id`      BIGINT       NOT NULL                COMMENT '内容作者ID(statusOf 的 userId)',
  `sharer_id`     BIGINT       NOT NULL                COMMENT '分享者用户ID',
  `snapshot_json` TEXT         NOT NULL                COMMENT '卡片快照(仅元数据,不复制内容主体)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=有效 0=已失效',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_id` (`id`),
  KEY `idx_user` (`sharer_id`, `id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='share_record(物理分片 turbo_feed_2.share_record_127)';
