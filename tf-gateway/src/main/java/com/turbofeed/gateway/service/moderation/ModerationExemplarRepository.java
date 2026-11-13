package com.turbofeed.gateway.service.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 坏样本库持久层（JDBC，单表落 ds_0）。
 *
 * <p>语义层 sidecar 从这里加载样本做近邻检索；人审 CONFIRMED 的漏放样本补录进此表，
 * 形成「拦不住 → 人审判违规 → 样本入库 → 下次挡住」的闭环。
 * 表登记见 shardingsphere-config*.yaml 的 !SINGLE 段（moderation_exemplar）。</p>
 */
@Repository
public class ModerationExemplarRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModerationExemplarRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ModerationExemplar> MAPPER = (rs, rn) -> {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ModerationExemplar(
                rs.getLong("id"),
                rs.getString("exemplar_text"),
                rs.getString("category"),
                rs.getString("source"),
                rs.getInt("enabled") == 1,
                created == null ? Instant.EPOCH : created.toInstant(),
                updated == null ? null : updated.toInstant());
    };

    /** 运营手工新增坏样本。 */
    public void insert(String text, String category, String source) {
        jdbcTemplate.update(
                "INSERT INTO moderation_exemplar (exemplar_text, category, source, enabled) VALUES (?, ?, ?, 1)",
                text, category, source == null ? "MANUAL" : source);
    }

    /** 分页列出（按分类过滤）。 */
    public List<ModerationExemplar> list(String category, boolean includeDisabled, int limit, long offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, exemplar_text, category, source, enabled, created_at, updated_at "
                        + "FROM moderation_exemplar WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (!includeDisabled) {
            sql.append(" AND enabled = 1");
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 软停用（误录样本不删，保留审计）。 */
    public void disable(long id) {
        jdbcTemplate.update("UPDATE moderation_exemplar SET enabled = 0 WHERE id = ?", id);
    }

    /** 重新启用。 */
    public void enable(long id) {
        jdbcTemplate.update("UPDATE moderation_exemplar SET enabled = 1 WHERE id = ?", id);
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_exemplar", Long.class);
        return c == null ? 0L : c;
    }
}
