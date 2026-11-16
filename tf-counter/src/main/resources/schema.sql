-- tf-counter 聚合计数表（自有库，与业务分片库隔离）
-- H2 嵌入式库（jdbc:h2:mem:tf-counter;MODE=MySQL）启动时由 spring.sql.init(embedded) 自动执行；
-- 生产切 MySQL 8 时改由迁移脚本管理，本文件仅作本地开发与契约参考。
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

-- ShedLock 分布式锁表（仅集群模式 redis-buffer-enabled=true 时由 ShedLockConfig 启用）。
-- 选主保证多 tf-counter 副本中只有一个执行 drain；JdbcLockProvider 复用本服务 DataSource。
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
