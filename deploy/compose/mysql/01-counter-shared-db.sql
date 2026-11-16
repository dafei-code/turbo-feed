-- =============================================================================
-- 阶段 B1：tf-counter 共享库（多副本下 ShedLock / 计数 / flush 去重的唯一事实源）
--
-- 与 tf-counter/src/main/resources/schema.sql 的表定义逐字一致，改动时两边必须同步：
-- schema.sql 管本地 H2 开发，本文件管 prod MySQL。二者漂移会导致「本地通过、生产缺列」。
--
-- ⚠️ docker-entrypoint-initdb.d 只在【空数据卷】首次初始化时执行。
--    已有环境需手工执行本文件，重启容器不会自动补建。
-- =============================================================================
CREATE DATABASE IF NOT EXISTS turbo_feed_counter
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE turbo_feed_counter;

-- 计数聚合结果表
CREATE TABLE IF NOT EXISTS post_counter (
  post_id            VARCHAR(255) NOT NULL PRIMARY KEY COMMENT '帖子身份（=时间线幂等键 postId）',
  like_count         BIGINT       NOT NULL DEFAULT 0 COMMENT '点赞聚合计数（最终一致）',
  comment_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '评论聚合计数（最终一致）',
  share_count        BIGINT       NOT NULL DEFAULT 0 COMMENT '分享聚合计数（最终一致）',
  comment_like_count BIGINT       NOT NULL DEFAULT 0 COMMENT '评论点赞聚合计数（最终一致，网关 CommentLikeService 发 COMMENT_LIKE 增量）',
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次落库时间'
);

-- flush 幂等去重：同一批次重放时已登记的帖直接跳过 UPSERT，杜绝重复累加
CREATE TABLE IF NOT EXISTS counter_flush_dedup (
  batch_id   VARCHAR(64)  NOT NULL COMMENT '本次 flush 周期唯一标识（UUID）',
  post_id    VARCHAR(255) NOT NULL COMMENT '帖子身份',
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（供清理）',
  PRIMARY KEY (batch_id, post_id)
);

-- ShedLock 分布式锁表：JdbcLockProvider 复用本服务 DataSource，
-- 只有在【共享库】里多副本才真正互斥；放在各副本 H2 里等于没锁。
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP    NOT NULL,
  locked_at  TIMESTAMP    NULL,
  locked_by  VARCHAR(255) NULL,
  PRIMARY KEY (name)
);
