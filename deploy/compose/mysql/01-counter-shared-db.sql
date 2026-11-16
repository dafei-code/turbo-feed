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

-- tf-counter 聚合计数表（自有库，与业务分片库隔离）
CREATE TABLE IF NOT EXISTS post_counter (
  post_id      VARCHAR(255) NOT NULL PRIMARY KEY COMMENT '帖子身份（=时间线幂等键 postId）',
  like_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '点赞聚合计数（最终一致）',
  comment_count BIGINT      NOT NULL DEFAULT 0 COMMENT '评论聚合计数（最终一致）',
  share_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '分享聚合计数（最终一致）',
  comment_like_count BIGINT NOT NULL DEFAULT 0 COMMENT '评论点赞聚合计数（最终一致，网关 CommentLikeService 发 COMMENT_LIKE 增量）',
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次落库时间'
);

-- flush 幂等去重表：每次 flush 周期生成 batch_id，逐帖登记 (batch_id, post_id)。
-- 同一批次重放（ShedLock 锁过期续跑 / 进程崩溃重试）时，已登记的帖直接跳过 UPSERT，
-- 杜绝重复累加。PK 为 (batch_id, post_id)，不同批次的同帖各自合法落库。
CREATE TABLE IF NOT EXISTS counter_flush_dedup (
  batch_id  VARCHAR(64)  NOT NULL COMMENT '本次 flush 周期唯一标识（UUID）',
  post_id   VARCHAR(255) NOT NULL COMMENT '帖子身份',
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（供清理）',
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

-- PR-E 失败批次暂存：flush 落库失败时整份快照连同原 batch_id 落此处，
-- 下个周期（含重启后首个周期）复用原 batch_id 重放，命中 counter_flush_dedup 才是真幂等。
-- payload 为 JSON：{postId:[like,comment,share,commentLike]}；仅 dry-run 报告与重放读取。
CREATE TABLE IF NOT EXISTS counter_flush_pending (
  batch_id   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '原 flush 批次 id（重放必须复用，换新 id 会重复累加）',
  payload    TEXT         NOT NULL COMMENT '增量快照 JSON: {postId:[like,comment,share,commentLike]}',
  attempts   INT          NOT NULL DEFAULT 0 COMMENT '重放失败次数累计',
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次登记时间（重放按此排序）',
  last_error VARCHAR(512) NULL COMMENT '最近一次失败原因（截断到 512 字符）'
);
