-- 供 docker-compose MySQL 初始化（/docker-entrypoint-initdb.d/00- 先于 01-init-local.sql 执行）
-- ShardingSphere 逻辑库 turbo_feed_1 / turbo_feed_2 需预先存在；
-- init-local.sql 以「库名.表名」全限定建表，无需 USE，故仅建库即可。
CREATE DATABASE IF NOT EXISTS turbo_feed_1
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS turbo_feed_2
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
