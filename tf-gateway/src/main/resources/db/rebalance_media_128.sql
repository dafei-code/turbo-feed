-- =============================================================================
-- turbo-feed 媒体表 存量再平衡：4 分片 -> 128 分片
-- -----------------------------------------------------------------------------
-- ⚠️⚠️ 这是**数据搬迁**语句，不是建表语句。请勿与建表脚本混跑。
--     前置：已执行 db/expand_media_tables_128.sql（128 张目标表已存在）。
--     建议：先在备份库演练；生产用双写 + 灰度，勿停机硬切（见 changelog 0041）。
--
-- 路由契约（与 ShardingSphere HASH_MOD 实证一致）：
--     idx  = Math.abs(user_id.hashCode()) % N
--     库   = idx % 2        ds_0=turbo_feed_1 / ds_1=turbo_feed_2
--     表   = idx
--   4 -> 128 的性质：idx128 % 4 == idx4，且 idx128 % 2 == idx4 % 2
--     => **源的库 == 目标的库**，搬迁全程库内完成，不跨库、不跨机。
--     源 media_X (X∈{0,1,2,3}) 拆分到 Y ∈ {X, X+4, ..., X+124} 共 32 张目标表。
--
-- 下面 MySQL 表达式与 Java Math.abs(hashCode())%128 **逐值一致**
--   （已用 29 个 id × {2,4,128} 交叉验证，见 changelog 0041），故可纯 SQL 搬迁：
--     ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED)))
--
-- ⚠️ 成本提示：每个源表要被扫 32 次（一次对应一张目标表）。小数据量无妨；
--    千万行以上请改为程序化搬迁（按 media_id 分段读 + 批量写），或先建生成列并加索引。
-- =============================================================================

-- -------------------- 源 turbo_feed_1.media_0 -> 32 张目标表 --------------------
INSERT IGNORE INTO `turbo_feed_1`.`media_0` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 0;
INSERT IGNORE INTO `turbo_feed_1`.`media_4` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 4;
INSERT IGNORE INTO `turbo_feed_1`.`media_8` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 8;
INSERT IGNORE INTO `turbo_feed_1`.`media_12` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 12;
INSERT IGNORE INTO `turbo_feed_1`.`media_16` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 16;
INSERT IGNORE INTO `turbo_feed_1`.`media_20` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 20;
INSERT IGNORE INTO `turbo_feed_1`.`media_24` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 24;
INSERT IGNORE INTO `turbo_feed_1`.`media_28` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 28;
INSERT IGNORE INTO `turbo_feed_1`.`media_32` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 32;
INSERT IGNORE INTO `turbo_feed_1`.`media_36` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 36;
INSERT IGNORE INTO `turbo_feed_1`.`media_40` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 40;
INSERT IGNORE INTO `turbo_feed_1`.`media_44` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 44;
INSERT IGNORE INTO `turbo_feed_1`.`media_48` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 48;
INSERT IGNORE INTO `turbo_feed_1`.`media_52` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 52;
INSERT IGNORE INTO `turbo_feed_1`.`media_56` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 56;
INSERT IGNORE INTO `turbo_feed_1`.`media_60` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 60;
INSERT IGNORE INTO `turbo_feed_1`.`media_64` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 64;
INSERT IGNORE INTO `turbo_feed_1`.`media_68` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 68;
INSERT IGNORE INTO `turbo_feed_1`.`media_72` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 72;
INSERT IGNORE INTO `turbo_feed_1`.`media_76` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 76;
INSERT IGNORE INTO `turbo_feed_1`.`media_80` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 80;
INSERT IGNORE INTO `turbo_feed_1`.`media_84` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 84;
INSERT IGNORE INTO `turbo_feed_1`.`media_88` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 88;
INSERT IGNORE INTO `turbo_feed_1`.`media_92` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 92;
INSERT IGNORE INTO `turbo_feed_1`.`media_96` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 96;
INSERT IGNORE INTO `turbo_feed_1`.`media_100` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 100;
INSERT IGNORE INTO `turbo_feed_1`.`media_104` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 104;
INSERT IGNORE INTO `turbo_feed_1`.`media_108` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 108;
INSERT IGNORE INTO `turbo_feed_1`.`media_112` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 112;
INSERT IGNORE INTO `turbo_feed_1`.`media_116` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 116;
INSERT IGNORE INTO `turbo_feed_1`.`media_120` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 120;
INSERT IGNORE INTO `turbo_feed_1`.`media_124` SELECT * FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 124;

-- -------------------- 源 turbo_feed_1.media_2 -> 32 张目标表 --------------------
INSERT IGNORE INTO `turbo_feed_1`.`media_2` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 2;
INSERT IGNORE INTO `turbo_feed_1`.`media_6` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 6;
INSERT IGNORE INTO `turbo_feed_1`.`media_10` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 10;
INSERT IGNORE INTO `turbo_feed_1`.`media_14` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 14;
INSERT IGNORE INTO `turbo_feed_1`.`media_18` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 18;
INSERT IGNORE INTO `turbo_feed_1`.`media_22` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 22;
INSERT IGNORE INTO `turbo_feed_1`.`media_26` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 26;
INSERT IGNORE INTO `turbo_feed_1`.`media_30` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 30;
INSERT IGNORE INTO `turbo_feed_1`.`media_34` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 34;
INSERT IGNORE INTO `turbo_feed_1`.`media_38` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 38;
INSERT IGNORE INTO `turbo_feed_1`.`media_42` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 42;
INSERT IGNORE INTO `turbo_feed_1`.`media_46` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 46;
INSERT IGNORE INTO `turbo_feed_1`.`media_50` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 50;
INSERT IGNORE INTO `turbo_feed_1`.`media_54` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 54;
INSERT IGNORE INTO `turbo_feed_1`.`media_58` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 58;
INSERT IGNORE INTO `turbo_feed_1`.`media_62` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 62;
INSERT IGNORE INTO `turbo_feed_1`.`media_66` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 66;
INSERT IGNORE INTO `turbo_feed_1`.`media_70` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 70;
INSERT IGNORE INTO `turbo_feed_1`.`media_74` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 74;
INSERT IGNORE INTO `turbo_feed_1`.`media_78` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 78;
INSERT IGNORE INTO `turbo_feed_1`.`media_82` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 82;
INSERT IGNORE INTO `turbo_feed_1`.`media_86` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 86;
INSERT IGNORE INTO `turbo_feed_1`.`media_90` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 90;
INSERT IGNORE INTO `turbo_feed_1`.`media_94` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 94;
INSERT IGNORE INTO `turbo_feed_1`.`media_98` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 98;
INSERT IGNORE INTO `turbo_feed_1`.`media_102` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 102;
INSERT IGNORE INTO `turbo_feed_1`.`media_106` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 106;
INSERT IGNORE INTO `turbo_feed_1`.`media_110` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 110;
INSERT IGNORE INTO `turbo_feed_1`.`media_114` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 114;
INSERT IGNORE INTO `turbo_feed_1`.`media_118` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 118;
INSERT IGNORE INTO `turbo_feed_1`.`media_122` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 122;
INSERT IGNORE INTO `turbo_feed_1`.`media_126` SELECT * FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 126;

-- -------------------- 源 turbo_feed_2.media_1 -> 32 张目标表 --------------------
INSERT IGNORE INTO `turbo_feed_2`.`media_1` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 1;
INSERT IGNORE INTO `turbo_feed_2`.`media_5` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 5;
INSERT IGNORE INTO `turbo_feed_2`.`media_9` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 9;
INSERT IGNORE INTO `turbo_feed_2`.`media_13` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 13;
INSERT IGNORE INTO `turbo_feed_2`.`media_17` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 17;
INSERT IGNORE INTO `turbo_feed_2`.`media_21` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 21;
INSERT IGNORE INTO `turbo_feed_2`.`media_25` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 25;
INSERT IGNORE INTO `turbo_feed_2`.`media_29` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 29;
INSERT IGNORE INTO `turbo_feed_2`.`media_33` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 33;
INSERT IGNORE INTO `turbo_feed_2`.`media_37` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 37;
INSERT IGNORE INTO `turbo_feed_2`.`media_41` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 41;
INSERT IGNORE INTO `turbo_feed_2`.`media_45` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 45;
INSERT IGNORE INTO `turbo_feed_2`.`media_49` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 49;
INSERT IGNORE INTO `turbo_feed_2`.`media_53` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 53;
INSERT IGNORE INTO `turbo_feed_2`.`media_57` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 57;
INSERT IGNORE INTO `turbo_feed_2`.`media_61` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 61;
INSERT IGNORE INTO `turbo_feed_2`.`media_65` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 65;
INSERT IGNORE INTO `turbo_feed_2`.`media_69` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 69;
INSERT IGNORE INTO `turbo_feed_2`.`media_73` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 73;
INSERT IGNORE INTO `turbo_feed_2`.`media_77` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 77;
INSERT IGNORE INTO `turbo_feed_2`.`media_81` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 81;
INSERT IGNORE INTO `turbo_feed_2`.`media_85` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 85;
INSERT IGNORE INTO `turbo_feed_2`.`media_89` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 89;
INSERT IGNORE INTO `turbo_feed_2`.`media_93` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 93;
INSERT IGNORE INTO `turbo_feed_2`.`media_97` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 97;
INSERT IGNORE INTO `turbo_feed_2`.`media_101` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 101;
INSERT IGNORE INTO `turbo_feed_2`.`media_105` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 105;
INSERT IGNORE INTO `turbo_feed_2`.`media_109` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 109;
INSERT IGNORE INTO `turbo_feed_2`.`media_113` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 113;
INSERT IGNORE INTO `turbo_feed_2`.`media_117` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 117;
INSERT IGNORE INTO `turbo_feed_2`.`media_121` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 121;
INSERT IGNORE INTO `turbo_feed_2`.`media_125` SELECT * FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 125;

-- -------------------- 源 turbo_feed_2.media_3 -> 32 张目标表 --------------------
INSERT IGNORE INTO `turbo_feed_2`.`media_3` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 3;
INSERT IGNORE INTO `turbo_feed_2`.`media_7` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 7;
INSERT IGNORE INTO `turbo_feed_2`.`media_11` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 11;
INSERT IGNORE INTO `turbo_feed_2`.`media_15` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 15;
INSERT IGNORE INTO `turbo_feed_2`.`media_19` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 19;
INSERT IGNORE INTO `turbo_feed_2`.`media_23` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 23;
INSERT IGNORE INTO `turbo_feed_2`.`media_27` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 27;
INSERT IGNORE INTO `turbo_feed_2`.`media_31` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 31;
INSERT IGNORE INTO `turbo_feed_2`.`media_35` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 35;
INSERT IGNORE INTO `turbo_feed_2`.`media_39` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 39;
INSERT IGNORE INTO `turbo_feed_2`.`media_43` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 43;
INSERT IGNORE INTO `turbo_feed_2`.`media_47` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 47;
INSERT IGNORE INTO `turbo_feed_2`.`media_51` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 51;
INSERT IGNORE INTO `turbo_feed_2`.`media_55` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 55;
INSERT IGNORE INTO `turbo_feed_2`.`media_59` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 59;
INSERT IGNORE INTO `turbo_feed_2`.`media_63` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 63;
INSERT IGNORE INTO `turbo_feed_2`.`media_67` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 67;
INSERT IGNORE INTO `turbo_feed_2`.`media_71` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 71;
INSERT IGNORE INTO `turbo_feed_2`.`media_75` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 75;
INSERT IGNORE INTO `turbo_feed_2`.`media_79` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 79;
INSERT IGNORE INTO `turbo_feed_2`.`media_83` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 83;
INSERT IGNORE INTO `turbo_feed_2`.`media_87` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 87;
INSERT IGNORE INTO `turbo_feed_2`.`media_91` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 91;
INSERT IGNORE INTO `turbo_feed_2`.`media_95` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 95;
INSERT IGNORE INTO `turbo_feed_2`.`media_99` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 99;
INSERT IGNORE INTO `turbo_feed_2`.`media_103` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 103;
INSERT IGNORE INTO `turbo_feed_2`.`media_107` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 107;
INSERT IGNORE INTO `turbo_feed_2`.`media_111` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 111;
INSERT IGNORE INTO `turbo_feed_2`.`media_115` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 115;
INSERT IGNORE INTO `turbo_feed_2`.`media_119` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 119;
INSERT IGNORE INTO `turbo_feed_2`.`media_123` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 123;
INSERT IGNORE INTO `turbo_feed_2`.`media_127` SELECT * FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 = 127;

-- =============================================================================
-- 阶段 2：清理源表中「已经搬走」的行
-- -----------------------------------------------------------------------------
-- ⚠️ 关键：media_0..media_3 本身就是 128 张目标表的成员（下标 0/1/2/3）。
--    所以「搬迁」= 「复制到新表」+「从旧表删掉不属于它的行」，两步缺一不可：
--    只复制不删 -> 同一条 media_id 存在两份，切换后重复计数（干跑实测 123 -> 246）。
--    只删不复制 -> 直接丢数据。
--    保留条件：expr % 128 恰好等于自己的下标（即 idx ∈ {0,1,2,3} 的那部分行原地不动）。
-- =============================================================================

DELETE FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 0;
DELETE FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 2;
DELETE FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 1;
DELETE FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 3;

-- =============================================================================
-- 阶段 3：逐表错位校验（**必须全 0 才能切配置**）
-- -----------------------------------------------------------------------------
-- 检查每张表里是否存在「按 HASH_MOD 不该在这张表」的行。
-- 期望：128 行全部 misplaced = 0。
-- =============================================================================

SELECT SUM(misplaced) AS total_misplaced FROM (
  SELECT 0 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_0` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 0
  UNION ALL
  SELECT 1 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_1` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 1
  UNION ALL
  SELECT 2 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_2` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 2
  UNION ALL
  SELECT 3 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_3` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 3
  UNION ALL
  SELECT 4 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_4` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 4
  UNION ALL
  SELECT 5 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_5` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 5
  UNION ALL
  SELECT 6 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_6` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 6
  UNION ALL
  SELECT 7 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_7` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 7
  UNION ALL
  SELECT 8 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_8` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 8
  UNION ALL
  SELECT 9 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_9` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 9
  UNION ALL
  SELECT 10 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_10` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 10
  UNION ALL
  SELECT 11 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_11` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 11
  UNION ALL
  SELECT 12 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_12` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 12
  UNION ALL
  SELECT 13 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_13` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 13
  UNION ALL
  SELECT 14 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_14` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 14
  UNION ALL
  SELECT 15 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_15` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 15
  UNION ALL
  SELECT 16 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_16` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 16
  UNION ALL
  SELECT 17 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_17` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 17
  UNION ALL
  SELECT 18 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_18` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 18
  UNION ALL
  SELECT 19 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_19` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 19
  UNION ALL
  SELECT 20 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_20` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 20
  UNION ALL
  SELECT 21 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_21` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 21
  UNION ALL
  SELECT 22 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_22` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 22
  UNION ALL
  SELECT 23 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_23` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 23
  UNION ALL
  SELECT 24 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_24` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 24
  UNION ALL
  SELECT 25 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_25` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 25
  UNION ALL
  SELECT 26 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_26` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 26
  UNION ALL
  SELECT 27 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_27` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 27
  UNION ALL
  SELECT 28 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_28` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 28
  UNION ALL
  SELECT 29 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_29` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 29
  UNION ALL
  SELECT 30 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_30` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 30
  UNION ALL
  SELECT 31 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_31` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 31
  UNION ALL
  SELECT 32 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_32` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 32
  UNION ALL
  SELECT 33 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_33` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 33
  UNION ALL
  SELECT 34 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_34` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 34
  UNION ALL
  SELECT 35 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_35` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 35
  UNION ALL
  SELECT 36 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_36` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 36
  UNION ALL
  SELECT 37 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_37` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 37
  UNION ALL
  SELECT 38 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_38` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 38
  UNION ALL
  SELECT 39 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_39` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 39
  UNION ALL
  SELECT 40 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_40` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 40
  UNION ALL
  SELECT 41 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_41` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 41
  UNION ALL
  SELECT 42 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_42` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 42
  UNION ALL
  SELECT 43 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_43` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 43
  UNION ALL
  SELECT 44 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_44` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 44
  UNION ALL
  SELECT 45 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_45` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 45
  UNION ALL
  SELECT 46 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_46` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 46
  UNION ALL
  SELECT 47 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_47` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 47
  UNION ALL
  SELECT 48 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_48` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 48
  UNION ALL
  SELECT 49 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_49` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 49
  UNION ALL
  SELECT 50 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_50` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 50
  UNION ALL
  SELECT 51 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_51` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 51
  UNION ALL
  SELECT 52 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_52` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 52
  UNION ALL
  SELECT 53 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_53` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 53
  UNION ALL
  SELECT 54 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_54` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 54
  UNION ALL
  SELECT 55 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_55` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 55
  UNION ALL
  SELECT 56 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_56` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 56
  UNION ALL
  SELECT 57 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_57` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 57
  UNION ALL
  SELECT 58 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_58` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 58
  UNION ALL
  SELECT 59 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_59` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 59
  UNION ALL
  SELECT 60 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_60` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 60
  UNION ALL
  SELECT 61 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_61` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 61
  UNION ALL
  SELECT 62 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_62` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 62
  UNION ALL
  SELECT 63 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_63` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 63
  UNION ALL
  SELECT 64 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_64` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 64
  UNION ALL
  SELECT 65 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_65` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 65
  UNION ALL
  SELECT 66 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_66` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 66
  UNION ALL
  SELECT 67 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_67` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 67
  UNION ALL
  SELECT 68 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_68` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 68
  UNION ALL
  SELECT 69 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_69` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 69
  UNION ALL
  SELECT 70 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_70` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 70
  UNION ALL
  SELECT 71 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_71` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 71
  UNION ALL
  SELECT 72 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_72` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 72
  UNION ALL
  SELECT 73 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_73` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 73
  UNION ALL
  SELECT 74 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_74` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 74
  UNION ALL
  SELECT 75 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_75` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 75
  UNION ALL
  SELECT 76 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_76` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 76
  UNION ALL
  SELECT 77 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_77` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 77
  UNION ALL
  SELECT 78 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_78` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 78
  UNION ALL
  SELECT 79 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_79` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 79
  UNION ALL
  SELECT 80 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_80` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 80
  UNION ALL
  SELECT 81 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_81` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 81
  UNION ALL
  SELECT 82 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_82` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 82
  UNION ALL
  SELECT 83 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_83` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 83
  UNION ALL
  SELECT 84 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_84` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 84
  UNION ALL
  SELECT 85 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_85` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 85
  UNION ALL
  SELECT 86 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_86` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 86
  UNION ALL
  SELECT 87 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_87` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 87
  UNION ALL
  SELECT 88 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_88` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 88
  UNION ALL
  SELECT 89 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_89` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 89
  UNION ALL
  SELECT 90 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_90` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 90
  UNION ALL
  SELECT 91 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_91` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 91
  UNION ALL
  SELECT 92 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_92` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 92
  UNION ALL
  SELECT 93 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_93` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 93
  UNION ALL
  SELECT 94 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_94` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 94
  UNION ALL
  SELECT 95 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_95` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 95
  UNION ALL
  SELECT 96 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_96` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 96
  UNION ALL
  SELECT 97 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_97` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 97
  UNION ALL
  SELECT 98 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_98` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 98
  UNION ALL
  SELECT 99 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_99` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 99
  UNION ALL
  SELECT 100 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_100` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 100
  UNION ALL
  SELECT 101 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_101` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 101
  UNION ALL
  SELECT 102 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_102` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 102
  UNION ALL
  SELECT 103 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_103` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 103
  UNION ALL
  SELECT 104 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_104` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 104
  UNION ALL
  SELECT 105 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_105` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 105
  UNION ALL
  SELECT 106 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_106` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 106
  UNION ALL
  SELECT 107 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_107` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 107
  UNION ALL
  SELECT 108 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_108` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 108
  UNION ALL
  SELECT 109 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_109` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 109
  UNION ALL
  SELECT 110 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_110` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 110
  UNION ALL
  SELECT 111 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_111` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 111
  UNION ALL
  SELECT 112 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_112` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 112
  UNION ALL
  SELECT 113 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_113` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 113
  UNION ALL
  SELECT 114 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_114` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 114
  UNION ALL
  SELECT 115 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_115` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 115
  UNION ALL
  SELECT 116 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_116` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 116
  UNION ALL
  SELECT 117 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_117` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 117
  UNION ALL
  SELECT 118 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_118` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 118
  UNION ALL
  SELECT 119 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_119` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 119
  UNION ALL
  SELECT 120 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_120` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 120
  UNION ALL
  SELECT 121 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_121` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 121
  UNION ALL
  SELECT 122 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_122` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 122
  UNION ALL
  SELECT 123 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_123` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 123
  UNION ALL
  SELECT 124 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_124` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 124
  UNION ALL
  SELECT 125 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_125` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 125
  UNION ALL
  SELECT 126 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_1`.`media_126` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 126
  UNION ALL
  SELECT 127 AS shard_idx, COUNT(*) AS misplaced FROM `turbo_feed_2`.`media_127` WHERE ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296, CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128 <> 127
) t;

-- 行数守恒校验：应与搬迁前的总行数一致
SELECT SUM(cnt) AS total_rows FROM (
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_0`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_1`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_2`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_3`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_4`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_5`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_6`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_7`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_8`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_9`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_10`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_11`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_12`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_13`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_14`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_15`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_16`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_17`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_18`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_19`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_20`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_21`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_22`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_23`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_24`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_25`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_26`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_27`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_28`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_29`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_30`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_31`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_32`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_33`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_34`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_35`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_36`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_37`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_38`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_39`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_40`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_41`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_42`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_43`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_44`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_45`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_46`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_47`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_48`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_49`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_50`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_51`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_52`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_53`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_54`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_55`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_56`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_57`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_58`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_59`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_60`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_61`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_62`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_63`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_64`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_65`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_66`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_67`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_68`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_69`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_70`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_71`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_72`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_73`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_74`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_75`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_76`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_77`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_78`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_79`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_80`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_81`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_82`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_83`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_84`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_85`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_86`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_87`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_88`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_89`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_90`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_91`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_92`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_93`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_94`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_95`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_96`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_97`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_98`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_99`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_100`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_101`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_102`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_103`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_104`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_105`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_106`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_107`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_108`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_109`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_110`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_111`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_112`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_113`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_114`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_115`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_116`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_117`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_118`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_119`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_120`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_121`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_122`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_123`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_124`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_125`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_1`.`media_126`
  UNION ALL
  SELECT COUNT(*) AS cnt FROM `turbo_feed_2`.`media_127`
) t;
