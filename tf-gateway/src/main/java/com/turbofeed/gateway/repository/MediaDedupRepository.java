package com.turbofeed.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 上传内容去重指纹仓储（media_dedup 单表，ds_0）。
 *
 * <p>#6 per-user SHA-256 去重的持久层。表经 ShardingSphere {@code !SINGLE} 规则登记，
 * 由 {@code JdbcTemplate}（逻辑 DataSource）路由到物理库 ds_0，与 {@link ReviewTaskRepository}
 * 同源取径——单表聚合队列，不按用户分片广播。</p>
 *
 * <p><b>写入语义</b>：{@link #insertIgnore} 用 {@code INSERT IGNORE}（uk_user_hash 唯一约束），
 * 并发重复上传只有一行生效，其余静默忽略——天然幂等，不抛重复键。</p>
 *
 * <p><b>查询语义</b>：{@link #findByUserHash}（PER_USER 维度，带 user_id）/ {@link #findByHash}
 * （GLOBAL 维度，仅靠哈希点查首行）。两者均 fail-open 交由上层包裹——本类只做 SQL，
 * 不吞异常（与 ReviewTaskRepository 一致：异常透传给调用方日志）。</p>
 */
@Repository
public class MediaDedupRepository {

    /**
     * 显式构造器（不用 {@code @RequiredArgsConstructor}）：本机构建环境对新文件 Lombok 处理不生效。
     */
    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<DedupRecord> MAPPER = (ResultSet rs, int rowNum) -> new DedupRecord(
            rs.getLong("user_id"),
            rs.getString("media_hash"),
            rs.getString("canonical_media_id"),
            rs.getString("canonical_url"),
            rs.getTimestamp("created_at").toInstant());

    public MediaDedupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 (user_id, media_hash) 查首份指纹（PER_USER 维度）。 */
    public Optional<DedupRecord> findByUserHash(long userId, String mediaHash) {
        return jdbcTemplate.query(
                "SELECT user_id, media_hash, canonical_media_id, canonical_url, created_at "
                        + "FROM media_dedup WHERE user_id = ? AND media_hash = ? LIMIT 1",
                MAPPER, userId, mediaHash)
                .stream().findFirst();
    }

    /** 按 media_hash 查任意一行（GLOBAL 维度跨用户复用，返回先到的首份）。 */
    public Optional<DedupRecord> findByHash(String mediaHash) {
        return jdbcTemplate.query(
                "SELECT user_id, media_hash, canonical_media_id, canonical_url, created_at "
                        + "FROM media_dedup WHERE media_hash = ? LIMIT 1",
                MAPPER, mediaHash)
                .stream().findFirst();
    }

    /** 幂等记指纹：相同 (user_id, media_hash) 仅首行生效（INSERT IGNORE）。 */
    public void insertIgnore(long userId, String mediaHash, String canonicalMediaId, String canonicalUrl) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO media_dedup (user_id, media_hash, canonical_media_id, canonical_url, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, mediaHash, canonicalMediaId, canonicalUrl == null ? "" : canonicalUrl,
                Timestamp.from(Instant.now()));
    }

    /** 去重指纹记录（canonical = 首份落库的代表媒体）。 */
    public record DedupRecord(long userId, String mediaHash, String canonicalMediaId,
                              String canonicalUrl, Instant createdAt) {
    }
}
