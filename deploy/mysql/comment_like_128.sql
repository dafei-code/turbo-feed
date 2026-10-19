-- comment_like 物理分片（128 表，与 comment 同策略 hashMod128）。
-- 分片键 media_id；偶数下标落 ds_0=turbo_feed_1、奇数 ds_1=turbo_feed_2。
-- 去重权威在 Redis 64 槽 Set（CommentLikeService），本表仅灾备；主键 (comment_id, user_id) 防并发重复。
-- 配置见 shardingsphere-config(-128).yaml 的 comment_like autoTable（shardingAlgorithmName: hashMod128）。

CREATE TABLE `turbo_feed_1`.`comment_like_0` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_0)';

CREATE TABLE `turbo_feed_2`.`comment_like_1` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_1)';

CREATE TABLE `turbo_feed_1`.`comment_like_2` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_2)';

CREATE TABLE `turbo_feed_2`.`comment_like_3` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_3)';

CREATE TABLE `turbo_feed_1`.`comment_like_4` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_4)';

CREATE TABLE `turbo_feed_2`.`comment_like_5` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_5)';

CREATE TABLE `turbo_feed_1`.`comment_like_6` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_6)';

CREATE TABLE `turbo_feed_2`.`comment_like_7` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_7)';

CREATE TABLE `turbo_feed_1`.`comment_like_8` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_8)';

CREATE TABLE `turbo_feed_2`.`comment_like_9` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_9)';

CREATE TABLE `turbo_feed_1`.`comment_like_10` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_10)';

CREATE TABLE `turbo_feed_2`.`comment_like_11` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_11)';

CREATE TABLE `turbo_feed_1`.`comment_like_12` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_12)';

CREATE TABLE `turbo_feed_2`.`comment_like_13` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_13)';

CREATE TABLE `turbo_feed_1`.`comment_like_14` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_14)';

CREATE TABLE `turbo_feed_2`.`comment_like_15` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_15)';

CREATE TABLE `turbo_feed_1`.`comment_like_16` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_16)';

CREATE TABLE `turbo_feed_2`.`comment_like_17` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_17)';

CREATE TABLE `turbo_feed_1`.`comment_like_18` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_18)';

CREATE TABLE `turbo_feed_2`.`comment_like_19` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_19)';

CREATE TABLE `turbo_feed_1`.`comment_like_20` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_20)';

CREATE TABLE `turbo_feed_2`.`comment_like_21` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_21)';

CREATE TABLE `turbo_feed_1`.`comment_like_22` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_22)';

CREATE TABLE `turbo_feed_2`.`comment_like_23` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_23)';

CREATE TABLE `turbo_feed_1`.`comment_like_24` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_24)';

CREATE TABLE `turbo_feed_2`.`comment_like_25` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_25)';

CREATE TABLE `turbo_feed_1`.`comment_like_26` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_26)';

CREATE TABLE `turbo_feed_2`.`comment_like_27` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_27)';

CREATE TABLE `turbo_feed_1`.`comment_like_28` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_28)';

CREATE TABLE `turbo_feed_2`.`comment_like_29` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_29)';

CREATE TABLE `turbo_feed_1`.`comment_like_30` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_30)';

CREATE TABLE `turbo_feed_2`.`comment_like_31` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_31)';

CREATE TABLE `turbo_feed_1`.`comment_like_32` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_32)';

CREATE TABLE `turbo_feed_2`.`comment_like_33` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_33)';

CREATE TABLE `turbo_feed_1`.`comment_like_34` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_34)';

CREATE TABLE `turbo_feed_2`.`comment_like_35` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_35)';

CREATE TABLE `turbo_feed_1`.`comment_like_36` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_36)';

CREATE TABLE `turbo_feed_2`.`comment_like_37` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_37)';

CREATE TABLE `turbo_feed_1`.`comment_like_38` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_38)';

CREATE TABLE `turbo_feed_2`.`comment_like_39` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_39)';

CREATE TABLE `turbo_feed_1`.`comment_like_40` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_40)';

CREATE TABLE `turbo_feed_2`.`comment_like_41` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_41)';

CREATE TABLE `turbo_feed_1`.`comment_like_42` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_42)';

CREATE TABLE `turbo_feed_2`.`comment_like_43` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_43)';

CREATE TABLE `turbo_feed_1`.`comment_like_44` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_44)';

CREATE TABLE `turbo_feed_2`.`comment_like_45` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_45)';

CREATE TABLE `turbo_feed_1`.`comment_like_46` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_46)';

CREATE TABLE `turbo_feed_2`.`comment_like_47` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_47)';

CREATE TABLE `turbo_feed_1`.`comment_like_48` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_48)';

CREATE TABLE `turbo_feed_2`.`comment_like_49` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_49)';

CREATE TABLE `turbo_feed_1`.`comment_like_50` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_50)';

CREATE TABLE `turbo_feed_2`.`comment_like_51` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_51)';

CREATE TABLE `turbo_feed_1`.`comment_like_52` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_52)';

CREATE TABLE `turbo_feed_2`.`comment_like_53` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_53)';

CREATE TABLE `turbo_feed_1`.`comment_like_54` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_54)';

CREATE TABLE `turbo_feed_2`.`comment_like_55` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_55)';

CREATE TABLE `turbo_feed_1`.`comment_like_56` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_56)';

CREATE TABLE `turbo_feed_2`.`comment_like_57` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_57)';

CREATE TABLE `turbo_feed_1`.`comment_like_58` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_58)';

CREATE TABLE `turbo_feed_2`.`comment_like_59` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_59)';

CREATE TABLE `turbo_feed_1`.`comment_like_60` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_60)';

CREATE TABLE `turbo_feed_2`.`comment_like_61` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_61)';

CREATE TABLE `turbo_feed_1`.`comment_like_62` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_62)';

CREATE TABLE `turbo_feed_2`.`comment_like_63` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_63)';

CREATE TABLE `turbo_feed_1`.`comment_like_64` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_64)';

CREATE TABLE `turbo_feed_2`.`comment_like_65` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_65)';

CREATE TABLE `turbo_feed_1`.`comment_like_66` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_66)';

CREATE TABLE `turbo_feed_2`.`comment_like_67` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_67)';

CREATE TABLE `turbo_feed_1`.`comment_like_68` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_68)';

CREATE TABLE `turbo_feed_2`.`comment_like_69` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_69)';

CREATE TABLE `turbo_feed_1`.`comment_like_70` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_70)';

CREATE TABLE `turbo_feed_2`.`comment_like_71` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_71)';

CREATE TABLE `turbo_feed_1`.`comment_like_72` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_72)';

CREATE TABLE `turbo_feed_2`.`comment_like_73` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_73)';

CREATE TABLE `turbo_feed_1`.`comment_like_74` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_74)';

CREATE TABLE `turbo_feed_2`.`comment_like_75` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_75)';

CREATE TABLE `turbo_feed_1`.`comment_like_76` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_76)';

CREATE TABLE `turbo_feed_2`.`comment_like_77` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_77)';

CREATE TABLE `turbo_feed_1`.`comment_like_78` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_78)';

CREATE TABLE `turbo_feed_2`.`comment_like_79` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_79)';

CREATE TABLE `turbo_feed_1`.`comment_like_80` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_80)';

CREATE TABLE `turbo_feed_2`.`comment_like_81` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_81)';

CREATE TABLE `turbo_feed_1`.`comment_like_82` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_82)';

CREATE TABLE `turbo_feed_2`.`comment_like_83` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_83)';

CREATE TABLE `turbo_feed_1`.`comment_like_84` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_84)';

CREATE TABLE `turbo_feed_2`.`comment_like_85` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_85)';

CREATE TABLE `turbo_feed_1`.`comment_like_86` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_86)';

CREATE TABLE `turbo_feed_2`.`comment_like_87` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_87)';

CREATE TABLE `turbo_feed_1`.`comment_like_88` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_88)';

CREATE TABLE `turbo_feed_2`.`comment_like_89` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_89)';

CREATE TABLE `turbo_feed_1`.`comment_like_90` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_90)';

CREATE TABLE `turbo_feed_2`.`comment_like_91` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_91)';

CREATE TABLE `turbo_feed_1`.`comment_like_92` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_92)';

CREATE TABLE `turbo_feed_2`.`comment_like_93` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_93)';

CREATE TABLE `turbo_feed_1`.`comment_like_94` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_94)';

CREATE TABLE `turbo_feed_2`.`comment_like_95` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_95)';

CREATE TABLE `turbo_feed_1`.`comment_like_96` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_96)';

CREATE TABLE `turbo_feed_2`.`comment_like_97` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_97)';

CREATE TABLE `turbo_feed_1`.`comment_like_98` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_98)';

CREATE TABLE `turbo_feed_2`.`comment_like_99` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_99)';

CREATE TABLE `turbo_feed_1`.`comment_like_100` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_100)';

CREATE TABLE `turbo_feed_2`.`comment_like_101` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_101)';

CREATE TABLE `turbo_feed_1`.`comment_like_102` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_102)';

CREATE TABLE `turbo_feed_2`.`comment_like_103` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_103)';

CREATE TABLE `turbo_feed_1`.`comment_like_104` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_104)';

CREATE TABLE `turbo_feed_2`.`comment_like_105` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_105)';

CREATE TABLE `turbo_feed_1`.`comment_like_106` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_106)';

CREATE TABLE `turbo_feed_2`.`comment_like_107` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_107)';

CREATE TABLE `turbo_feed_1`.`comment_like_108` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_108)';

CREATE TABLE `turbo_feed_2`.`comment_like_109` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_109)';

CREATE TABLE `turbo_feed_1`.`comment_like_110` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_110)';

CREATE TABLE `turbo_feed_2`.`comment_like_111` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_111)';

CREATE TABLE `turbo_feed_1`.`comment_like_112` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_112)';

CREATE TABLE `turbo_feed_2`.`comment_like_113` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_113)';

CREATE TABLE `turbo_feed_1`.`comment_like_114` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_114)';

CREATE TABLE `turbo_feed_2`.`comment_like_115` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_115)';

CREATE TABLE `turbo_feed_1`.`comment_like_116` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_116)';

CREATE TABLE `turbo_feed_2`.`comment_like_117` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_117)';

CREATE TABLE `turbo_feed_1`.`comment_like_118` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_118)';

CREATE TABLE `turbo_feed_2`.`comment_like_119` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_119)';

CREATE TABLE `turbo_feed_1`.`comment_like_120` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_120)';

CREATE TABLE `turbo_feed_2`.`comment_like_121` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_121)';

CREATE TABLE `turbo_feed_1`.`comment_like_122` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_122)';

CREATE TABLE `turbo_feed_2`.`comment_like_123` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_123)';

CREATE TABLE `turbo_feed_1`.`comment_like_124` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_124)';

CREATE TABLE `turbo_feed_2`.`comment_like_125` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_125)';

CREATE TABLE `turbo_feed_1`.`comment_like_126` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_1.comment_like_126)';

CREATE TABLE `turbo_feed_2`.`comment_like_127` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID(应用层自生成)',
  `comment_id` BIGINT       NOT NULL                COMMENT '评论ID',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `idx_media_comment` (`media_id`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment_like(物理分片 turbo_feed_2.comment_like_127)';
