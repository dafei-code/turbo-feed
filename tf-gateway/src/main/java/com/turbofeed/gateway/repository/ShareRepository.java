package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.share.ShareRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 短链映射仓储（逻辑表 share_record，按 code HASH_MOD 128 分片）。
 *
 * <p><b>点查走分片键</b>：{@link #findByCode} 带 code，ShardingSphere 精准命中单分片。
 * <b>列表按 sharer_id 查</b>不带分片键，会广播 128 片——这是"我的分享"低频运营功能的已知代价，
 * 不为了它改变分片键（code 是跳转主链路的长期承诺，见方案 §8.1）。</p>
 *
 * <p>异常不吞，由 {@code ShareService} 统一 fail-open / 转错误码（与 MediaDedupRepository 同口径）。</p>
 */
@Repository
public class ShareRepository {

    private static final RowMapper<ShareRecord> MAPPER = (rs, rowNum) -> new ShareRecord(
            rs.getString("code"),
            rs.getLong("id"),
            rs.getString("post_id"),
            rs.getString("media_id"),
            rs.getLong("owner_id"),
            rs.getLong("sharer_id"),
            rs.getString("snapshot_json"),
            rs.getInt("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ShareRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按短码点查（分片键）；不存在返回 empty。 */
    public Optional<ShareRecord> findByCode(String code) {
        List<ShareRecord> rows = jdbcTemplate.query(
                "SELECT code, id, post_id, media_id, owner_id, sharer_id, snapshot_json, status, created_at "
                        + "FROM share_record WHERE code = ? LIMIT 1",
                MAPPER, code);
        return rows.stream().findFirst();
    }

    /**
     * 插入映射。返回影响行数：1 = 成功，0 = uk_code 冲突（并发同码，调用方换码重试）。
     */
    public int insert(ShareRecord record) {
        return jdbcTemplate.update(
                "INSERT INTO share_record (code, id, post_id, media_id, owner_id, sharer_id, "
                        + "snapshot_json, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.code(), record.id(), record.postId(), record.mediaId(),
                record.ownerId(), record.sharerId(), record.snapshotJson(),
                record.status(), Timestamp.from(record.createdAt()));
    }

    /** 我的分享列表（游标 = 上一页最小 id，按 id 倒序）。 */
    public List<ShareRecord> listByUser(long sharerId, long cursor, int limit) {
        String sql = cursor > 0
                ? "SELECT code, id, post_id, media_id, owner_id, sharer_id, snapshot_json, status, created_at "
                        + "FROM share_record WHERE sharer_id = ? AND status = 1 AND id < ? ORDER BY id DESC LIMIT ?"
                : "SELECT code, id, post_id, media_id, owner_id, sharer_id, snapshot_json, status, created_at "
                        + "FROM share_record WHERE sharer_id = ? AND status = 1 ORDER BY id DESC LIMIT ?";
        return cursor > 0
                ? jdbcTemplate.query(sql, MAPPER, sharerId, cursor, limit + 1)
                : jdbcTemplate.query(sql, MAPPER, sharerId, limit + 1);
    }

    /** 运营作废（内容下架链路调用）。 */
    public int invalidate(String code) {
        return jdbcTemplate.update(
                "UPDATE share_record SET status = ? WHERE code = ?", ShareRecord.STATUS_INVALID, code);
    }
}
