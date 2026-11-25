-- H2 测试表：与 share_record 逻辑表同构（分片路由由 ShardingSphere 负责，单测直连 H2）。
CREATE TABLE IF NOT EXISTS share_record (
  code          VARCHAR(16)   NOT NULL PRIMARY KEY,
  id            BIGINT        NOT NULL,
  post_id       VARCHAR(255)  NOT NULL,
  media_id      VARCHAR(255)  NOT NULL,
  owner_id      BIGINT        NOT NULL,
  sharer_id     BIGINT        NOT NULL,
  snapshot_json VARCHAR(4000) NOT NULL,
  status        INTEGER       NOT NULL DEFAULT 1,
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_share_user ON share_record(sharer_id, id);
CREATE INDEX IF NOT EXISTS idx_share_post ON share_record(post_id);
