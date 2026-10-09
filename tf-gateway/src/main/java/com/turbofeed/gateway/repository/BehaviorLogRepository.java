package com.turbofeed.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 异常行为日志仓储（behavior_log 单表，ds_0 = turbo_feed_1）。
 *
 * <p>与 {@link ReporterCreditRepository}/{@link AccountHealthRepository} 同套路（单表、JdbcTemplate、显式构造器、不依赖 Lombok）。
 * 该表经 ShardingSphere {@code !SINGLE} 托管（见 shardingsphere-config-128.yaml）。行为日志是<b>只写多、读少</b>的追加流，
 * 纯 INSERT，不 upsert、不更新（created_at 由 DB 默认）。</p>
 */
@Repository
public class BehaviorLogRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 显式构造器注入（不用 {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    public BehaviorLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条行为日志（media_id / detail 可空；created_at 由 DB 默认）。fail-open 由调用方 {@link BehaviorLogService} 兜。 */
    public void insert(long userId, String mediaId, String action, String detail) {
        jdbcTemplate.update(
                "INSERT INTO behavior_log (user_id, media_id, action, detail) VALUES (?, ?, ?, ?)",
                userId, mediaId, action, detail);
    }
}
