package com.turbofeed.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 交互行为事件仓储（interaction_event 单表，ds_0 = turbo_feed_1）。
 *
 * <p>与 {@link BehaviorLogRepository} 同套路（单表、JdbcTemplate、显式构造器、不依赖 Lombok）。
 * 该表经 ShardingSphere {@code !SINGLE} 托管（见 shardingsphere-config(-128).yaml）。交互事件
 * 是<b>只写多、读少</b>的追加流，纯 INSERT，不 upsert、不更新（created_at 由 DB 默认）。</p>
 *
 * <p>{@link #listSince} 是训练样本导出缝：离线双塔作业（A1）按时间窗拉取近 N 天三元组，
 * 落 MinIO(parquet/csv) 后训练。读路径同样 fail-open，由调用方兜底。</p>
 */
@Repository
public class InteractionEventRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 显式构造器注入（不用 {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    public InteractionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条交互事件（user_id/item_id/event_type 必填；其余可空；created_at 由 DB 默认）。fail-open 由调用方兜。 */
    public void insert(long userId, String itemId, String eventType, Integer position,
                       Integer page, String channel, Integer pool, String requestId, String ext) {
        jdbcTemplate.update(
                "INSERT INTO interaction_event (user_id, item_id, event_type, position, page, channel, pool, request_id, ext) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, itemId, eventType, position, page, channel, pool, requestId, ext);
    }

    /** 训练样本导出缝：拉取 created_at ≥ from 的最近 limit 条（离线双塔作业消费）。fail-open 由调用方兜。 */
    public List<InteractionEventRow> listSince(Instant from, int limit) {
        return jdbcTemplate.query(
                "SELECT user_id, item_id, event_type, position, page, channel, pool, request_id, created_at "
                        + "FROM interaction_event WHERE created_at >= ? ORDER BY created_at ASC LIMIT ?",
                (rs, i) -> mapRow(rs), from, limit);
    }

    private static InteractionEventRow mapRow(ResultSet rs) throws SQLException {
        return new InteractionEventRow(
                rs.getLong("user_id"),
                rs.getString("item_id"),
                rs.getString("event_type"),
                (Integer) rs.getObject("position"),
                (Integer) rs.getObject("page"),
                rs.getString("channel"),
                (Integer) rs.getObject("pool"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
