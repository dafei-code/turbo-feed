package com.turbofeed.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 用户兴趣标签持久化仓储（interest_tag 单表，ds_0 = turbo_feed_1）。
 *
 * <p>与 {@link InteractionEventRepository} 同套路（单表、JdbcTemplate、显式构造器、不依赖 Lombok）。
 * 该表经 ShardingSphere {@code !SINGLE} 托管。画像主计算在 feed-engine（Redis），
 * 本仓储只负责把网关 {@code InterestTagSnapshotJob} 定时扫出的画像 upsert 落库，作耐久/离线特征汇。</p>
 *
 * <p>fail-open 由调用方（快照作业）兜底：单条写失败不影响其余用户。</p>
 */
@Repository
public class InterestTagRepository {

    private final JdbcTemplate jdbcTemplate;

    public InterestTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** upsert 一条兴趣标签（同 (user_id,tag,layer) 覆盖权重）。fail-open 由调用方兜。 */
    public void upsert(long userId, String tag, double weight, String layer) {
        jdbcTemplate.update(
                "INSERT INTO interest_tag (user_id, tag, weight, layer, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE weight = VALUES(weight), updated_at = NOW()",
                userId, tag, weight, layer);
    }
}
