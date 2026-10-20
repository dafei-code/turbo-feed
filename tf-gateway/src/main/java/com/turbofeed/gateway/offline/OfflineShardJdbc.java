package com.turbofeed.gateway.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 离线作业的<b>物理分片直连</b>通道（绕开 ShardingSphere）。
 *
 * <h3>为什么要有这条通道</h3>
 * <p>正常的业务 SQL 走 ShardingSphere 逻辑表 {@code media_tag}，一次不带分片键的查询会被
 * <b>广播到 128 张物理表再归并排序</b>——分页时每一页都要把 128 份结果拉回来重新排序，
 * 数据量一大就是灾难。而离线重建恰恰是「全表扫描」型作业，正好撞在这个最坏路径上。</p>
 *
 * <p>直连物理库后可以<b>按分片逐个扫</b>：每张表独立游标、独立并发、独立断点，
 * 广播归并的开销彻底消失。这是离线作业与在线查询的根本区别——在线必须走逻辑表
 * （因为要按分片键路由），离线没有这个约束，就该用更高效的路径。</p>
 *
 * <h3>为什么按主键 {@code post_id} 做游标，而不是按时间</h3>
 * <p>物理表主键是 {@code (post_id, tag)}，{@code ORDER BY post_id} 能走主键最左前缀，
 * 是<b>索引顺序扫描</b>；而 {@code ORDER BY created_at} 在无索引列上每页都要全表排序。
 * 重建要的是「把全量数据过一遍」，并不要求按时间顺序写入（ZSET 自己会按 score 排），
 * 所以选最快的遍历顺序，而不是最"自然"的顺序。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
public class OfflineShardJdbc {

    private static final Logger log = LoggerFactory.getLogger(OfflineShardJdbc.class);

    /** 偶数下标分片所在库（与 {@code shardingsphere-config-local.yml} 的 autoTables 规则一致）。 */
    private static final String DS0_DB = "turbo_feed_1";
    /** 奇数下标分片所在库。 */
    private static final String DS1_DB = "turbo_feed_2";

    private final JdbcTemplate jdbc0;
    private final JdbcTemplate jdbc1;

    public OfflineShardJdbc(javax.sql.DataSource ds0, javax.sql.DataSource ds1) {
        this.jdbc0 = new JdbcTemplate(ds0);
        this.jdbc1 = new JdbcTemplate(ds1);
    }

    /** 单条冷源记录。 */
    public record Row(String postId, String tag, long createdAt) {
    }

    /**
     * 取某一物理分片的一页记录（主键游标）。
     *
     * @param shard        分片下标 0..127（偶数落 {@code turbo_feed_1}，奇数落 {@code turbo_feed_2}）
     * @param afterPostId  上一页最后一个 {@code post_id}；首页传 {@code null} 或空串
     * @param limit        页大小
     */
    public List<Row> page(int shard, String afterPostId, int limit) {
        boolean even = shard % 2 == 0;
        String table = (even ? DS0_DB : DS1_DB) + ".media_tag_" + shard;
        String sql = "SELECT post_id, tag, created_at FROM " + table
                + " WHERE post_id > ? ORDER BY post_id LIMIT " + limit;
        String cursor = afterPostId == null ? "" : afterPostId;
        JdbcTemplate jdbc = even ? jdbc0 : jdbc1;
        try {
            return jdbc.query(sql,
                    (rs, i) -> new Row(rs.getString("post_id"), rs.getString("tag"), rs.getLong("created_at")),
                    cursor);
        } catch (RuntimeException e) {
            log.warn("离线分片读取失败: table={}, {}", table, e.getMessage());
            throw e;
        }
    }
}
