-- tf-counter 聚合计数表（自有库，与业务分片库隔离）
-- H2 嵌入式库（jdbc:h2:mem:tf-counter;MODE=MySQL）启动时由 spring.sql.init(embedded) 自动执行；
-- 生产切 MySQL 8 时改由迁移脚本管理，本文件仅作本地开发与契约参考。
CREATE TABLE IF NOT EXISTS post_counter (
  post_id      VARCHAR(255) NOT NULL PRIMARY KEY COMMENT '帖子身份（=时间线幂等键 postId）',
  like_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '点赞聚合计数（最终一致）',
  comment_count BIGINT      NOT NULL DEFAULT 0 COMMENT '评论聚合计数（最终一致）',
  share_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '分享聚合计数（最终一致）',
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次落库时间'
);
