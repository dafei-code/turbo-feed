-- =============================================================================
-- turbo-feed 媒体元数据表【扩容版】建表语句：2 库 × 64 表 = 128 物理表
-- -----------------------------------------------------------------------------
-- 用途：承载百亿级媒体元数据行（单表约千万行，B+Tree 索引友好）。
--
-- ⚠️⚠️ 命名约定（2026-09-21 修正，见 docs/changelog/0041）：
--   本文件此前按「每库各 media_0..media_63」建表，与分片配置**不匹配**，切过去会直接
--   TableNotFoundException。正确约定是 **全局连续编号**：
--     idx = Math.abs(user_id.hashCode()) % 128      -- ShardingSphere HASH_MOD 实证
--     库   = idx % 2   -> ds_0(turbo_feed_1) 拿偶数下标 / ds_1(turbo_feed_2) 拿奇数下标
--     表   = idx       -> media_0 .. media_127
--   即 turbo_feed_1 持有 media_0/_2/_4/.../_126，turbo_feed_2 持有 media_1/_3/.../_127。
--   这是 autoTables（自动分片）的硬性规则：物理表由 sharding-count 推导，
--   第 i 张表落到 actualDataSources.get(i % 库数)，不存在「每库重新从 0 编号」。
--
-- ⚠️ 本文件已同步当前线上真实表结构（含 caption/caption_mark/post_id/seq 与 idx_user_post）。
--    旧版本缺这 4 列 1 索引，按旧版建表会让写文案/分组直接 ColumnNotFoundException。
--    权威结构来源：deploy/mysql/init-local.sql。
--
-- 切换步骤（见 docs/changelog/0041）：
--   1) 执行本文件，建立 128 张物理表；
--   2) 执行 db/rebalance_media_128.sql 做存量搬迁（4 片 -> 128 片）；
--   3) 将 application.yml 的 spring.datasource.url 指向 classpath:shardingsphere-config-128.yaml；
--   4) 重启应用（ShardingSphere 在启动时加载元数据，建表后不重启不生效）。
--
-- 💡 扩容性质（实证）：128 是 4 的 2 的幂倍扩展，故 idx128 % 4 == idx4，
--    且两者的「库」下标（%2）恒等 —— **扩容只换库内表号，不跨库迁移**。
--    例：user_id=1000000000000000000，4 片落在 ds_1.media_1，128 片落在 ds_1.media_77。
-- =============================================================================

-- -------------------- 库 turbo_feed_1（ds_0，偶数下标 0/2/.../126） --------------------
CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_0` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_0)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_2` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_2)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_4` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_4)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_6` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_6)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_8` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_8)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_10` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_10)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_12` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_12)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_14` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_14)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_16` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_16)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_18` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_18)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_20` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_20)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_22` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_22)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_24` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_24)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_26` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_26)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_28` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_28)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_30` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_30)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_32` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_32)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_34` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_34)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_36` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_36)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_38` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_38)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_40` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_40)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_42` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_42)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_44` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_44)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_46` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_46)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_48` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_48)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_50` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_50)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_52` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_52)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_54` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_54)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_56` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_56)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_58` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_58)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_60` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_60)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_62` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_62)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_64` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_64)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_66` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_66)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_68` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_68)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_70` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_70)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_72` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_72)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_74` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_74)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_76` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_76)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_78` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_78)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_80` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_80)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_82` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_82)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_84` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_84)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_86` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_86)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_88` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_88)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_90` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_90)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_92` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_92)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_94` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_94)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_96` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_96)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_98` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_98)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_100` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_100)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_102` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_102)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_104` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_104)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_106` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_106)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_108` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_108)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_110` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_110)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_112` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_112)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_114` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_114)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_116` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_116)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_118` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_118)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_120` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_120)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_122` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_122)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_124` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_124)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_126` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_126)';

-- -------------------- 库 turbo_feed_2（ds_1，奇数下标 1/3/.../127） --------------------
CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_1` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_1)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_3` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_3)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_5` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_5)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_7` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_7)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_9` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_9)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_11` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_11)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_13` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_13)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_15` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_15)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_17` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_17)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_19` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_19)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_21` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_21)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_23` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_23)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_25` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_25)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_27` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_27)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_29` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_29)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_31` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_31)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_33` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_33)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_35` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_35)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_37` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_37)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_39` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_39)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_41` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_41)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_43` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_43)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_45` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_45)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_47` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_47)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_49` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_49)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_51` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_51)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_53` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_53)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_55` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_55)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_57` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_57)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_59` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_59)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_61` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_61)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_63` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_63)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_65` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_65)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_67` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_67)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_69` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_69)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_71` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_71)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_73` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_73)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_75` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_75)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_77` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_77)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_79` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_79)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_81` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_81)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_83` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_83)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_85` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_85)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_87` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_87)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_89` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_89)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_91` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_91)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_93` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_93)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_95` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_95)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_97` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_97)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_99` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_99)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_101` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_101)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_103` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_103)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_105` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_105)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_107` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_107)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_109` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_109)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_111` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_111)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_113` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_113)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_115` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_115)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_117` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_117)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_119` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_119)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_121` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_121)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_123` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_123)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_125` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_125)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_127` (
  `media_id`    VARCHAR(255) NOT NULL COMMENT '内容唯一标识(业务主键)',
  `user_id`     BIGINT       NOT NULL COMMENT '归属用户ID, 分片键',
  `url`         VARCHAR(512) NOT NULL COMMENT '可访问地址',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type`  VARCHAR(16)  NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`         TINYINT      NOT NULL DEFAULT 0 COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_127)';

-- =============================================================================
-- 建表自检（执行完本文件后跑，期望 128 行，且每张表都非空校验通过）
-- =============================================================================
-- SELECT table_schema, COUNT(*) AS table_cnt,
--        MIN(CAST(SUBSTRING_INDEX(table_name, '_', -1) AS UNSIGNED)) AS min_idx,
--        MAX(CAST(SUBSTRING_INDEX(table_name, '_', -1) AS UNSIGNED)) AS max_idx
-- FROM information_schema.tables
-- WHERE table_schema IN ('turbo_feed_1','turbo_feed_2') AND table_name LIKE 'media\_%'
-- GROUP BY table_schema;
-- 期望：turbo_feed_1 -> 64 张，min_idx=0,  max_idx=126（全偶）
--       turbo_feed_2 -> 64 张，min_idx=1,  max_idx=127（全奇）
