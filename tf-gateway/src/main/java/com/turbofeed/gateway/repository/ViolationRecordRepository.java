package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.penalty.ViolationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 违规记录仓储（violation_record 表，<b>append-only</b>）。
 *
 * <p><b>存储形态</b>：{@code violation_record} 是 ShardingSphere {@code autoTables} 逻辑表，
 * 分片键 {@code user_id}（与 user / media / account_credit 同键同算法），物理上 4 张表
 * （ds_0 → {@code _0/_2}、ds_1 → {@code _1/_3}）。升级决策读它做类目/严重度聚合。</p>
 *
 * <p><b>主键</b>：{@code id} 由 ShardingSphere 雪花算法填充（见 shardingsphere-config.yaml 的
 * {@code keyGenerateStrategy}），故 INSERT <b>不传 id</b>；分片表的物理主键含分片键
 * {@code (user_id, id)}，保证按 user_id 查询单分片命中。</p>
 */
@Repository
public class ViolationRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 构造器注入（不用 Lombok，原因同 {@code AccountPenaltyRepository}）。 */
    public ViolationRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条违规记录（id 由 ShardingSphere 填充，append-only：不提供更新/删除方法，
     * 纠正靠新增 {@code source=APPEAL_OVERTURN} 的反向记录，不抹历史）。
     */
    public void insert(ViolationRecord r) {
        jdbcTemplate.update(
                "INSERT INTO violation_record (user_id, category, severity, source, action_taken, "
                        + "related_media_id, reason, operator, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.userId(),
                r.category().code(),
                r.severity().code(),
                r.source().code(),
                r.actionTaken() == null ? 0 : r.actionTaken().code(),
                r.relatedMediaId(),
                r.reason(),
                r.operator(),
                Timestamp.from(Instant.now()));
    }
}
