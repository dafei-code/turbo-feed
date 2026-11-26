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

    /**
     * 按 postId 反查该帖全部短链（含已失效的，便于重复下架时幂等清理缓存）。
     *
     * <p><b>不带分片键</b>会广播 128 片（code 是分片键），但内容下架是低频管理操作，
     * 与 {@link #listByUser} / {@link #scanCodesAfter} 同口径——不为它引入 postId→codes 的
     * Redis 反查索引（索引与 DB 双写有一致性窗口，下架时反而可能漏清）。DB 是权威源。</p>
     */
    public List<ShareRecord> findByPostId(String postId) {
        if (postId == null || postId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT code, id, post_id, media_id, owner_id, sharer_id, snapshot_json, status, created_at "
                        + "FROM share_record WHERE post_id = ?",
                MAPPER, postId);
    }

    /**
     * 下架单条短链：DB 状态置 INVALID 并把快照整体替换为打码后的 JSON（§8.1）。
     *
     * <p><b>按 code 更新</b>：code 是分片键，精准命中单分片，不广播。
     * 快照必须逐条按原记录重新生成（保留 owner_id / sharer_id / created_at 各自的值），
     * 不能用一条统一 JSON 覆盖整帖——那会把不同分享者的元数据冲成同一个。</p>
     *
     * <p>影响行数：1 = 实际翻转；0 = 已失效、不存在。配合 {@link #findByPostId} 由 Service
     * 逐条调用，下架一个帖子的几十条短链各自命中单分片，比一条广播 UPDATE 更省 DB。</p>
     */
    public int invalidateAndRedact(String code, String redactedJson) {
        return jdbcTemplate.update(
                "UPDATE share_record SET status = ?, snapshot_json = ? WHERE code = ? AND status = ?",
                ShareRecord.STATUS_INVALID, redactedJson, code, ShareRecord.STATUS_ACTIVE);
    }

    /**
     * 按 code 游标分页扫描全部短码（BF 回填专用，方案 §4.8.4）。
     *
     * <p>不带分片键会广播 128 片，但回填是启动时一次性的低频运维操作，可接受；
     * 每批按 code 字典序游标推进，批间让出 DB 由 Runner 负责。</p>
     */
    public List<String> scanCodesAfter(String cursor, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT code FROM share_record WHERE code > ? ORDER BY code LIMIT ?",
                String.class, cursor, limit);
    }

    /** 运营盘点：按状态/分享者过滤 + 游标分页（不带分片键，广播 128 片的低频管理操作）。 */
    public List<ShareRecord> listForAdmin(Integer status, Long sharerId, String cursor, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT code, id, post_id, media_id, owner_id, sharer_id, snapshot_json, status, created_at "
                        + "FROM share_record WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (sharerId != null && sharerId > 0) {
            sql.append(" AND sharer_id = ?");
            args.add(sharerId);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND code > ?");
            args.add(cursor);
        }
        sql.append(" ORDER BY code LIMIT ?");
        args.add(limit + 1);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }
}
