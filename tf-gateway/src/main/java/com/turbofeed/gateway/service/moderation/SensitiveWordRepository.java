package com.turbofeed.gateway.service.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 敏感词持久层（JDBC）。
 *
 * <p><b>表路由</b>：{@code sensitive_word} 单表放在 {@code turbo_feed_1}（ds_0），
 * 未注册到 ShardingSphere 的 autoTables/broadcastTables 规则，ShardingSphere 5.x
 * 会把未配置的表路由到默认数据源（本配置下即 ds_0）——词库小、单表读写，足够。
 * 若未来需多副本 HA，将该表注册为 BROADCAST 规则即可零侵入复制到 ds_1。</p>
 *
 * <p><b>变更检测</b>：{@link #checkSum()} 返回 {@code (COUNT, MAX(updated_at))}，
 * 任意 INSERT/UPDATE/DELETE 都会改变该二元组——无需应用层维护全局计数器，
 * 也避免 ON DUPLICATE KEY UPDATE 与 MAX 之间的子查询嵌套陷阱。</p>
 */
@Repository
@RequiredArgsConstructor
public class SensitiveWordRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, word, category, enabled, revision";

    private static final org.springframework.jdbc.core.RowMapper<SensitiveWord> MAPPER = (rs, rn) ->
            new SensitiveWord(
                    rs.getLong("id"),
                    rs.getString("word"),
                    rs.getString("category"),
                    rs.getBoolean("enabled"),
                    rs.getLong("revision"));

    /** 查询所有启用的词（用于构建 AC 自动机）。 */
    public List<SensitiveWord> findAllEnabled() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_word WHERE enabled = 1 ORDER BY id",
                MAPPER);
    }

    /** 分页查询全部词（含禁用），用于后台管理列表。 */
    public List<SensitiveWord> findAll(int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_word ORDER BY id LIMIT ? OFFSET ?",
                MAPPER, limit, offset);
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensitive_word", Long.class);
        return c == null ? 0L : c;
    }

    /**
     * 变更指纹：{@code (count, maxUpdatedAt)}。
     *
     * <p>INSERT → count↑ 或 maxUpdatedAt↑；UPDATE → maxUpdatedAt↑（{@code ON UPDATE CURRENT_TIMESTAMP}）；
     * DELETE → count↓。任一变化即视为词库有变更，需触发 AC 重建。空表返回
     * {@code (0, 1970-01-01)}，与首启动一致。</p>
     */
    public CheckSum checkSum() {
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensitive_word", Long.class);
        Timestamp maxTs = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(updated_at), '1970-01-01 00:00:00') FROM sensitive_word",
                Timestamp.class);
        return new CheckSum(cnt == null ? 0L : cnt,
                maxTs == null ? Instant.EPOCH : maxTs.toInstant());
    }

    /**
     * 新增 / 更新（按 word 唯一键 upsert）：存在则刷新 category、置 enabled=1、revision+1。
     * 返回 upsert 后的行。
     */
    public SensitiveWord upsert(String word, String category) {
        jdbcTemplate.update(
                "INSERT INTO sensitive_word (word, category, enabled, revision) VALUES (?, ?, 1, 1) "
                        + "ON DUPLICATE KEY UPDATE category = VALUES(category), enabled = 1, revision = revision + 1",
                word, category);
        List<SensitiveWord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_word WHERE word = ?", MAPPER, word);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 软删除（enabled=0，保留审计 + 可恢复）。 */
    public void disable(long id) {
        jdbcTemplate.update(
                "UPDATE sensitive_word SET enabled = 0, revision = revision + 1 WHERE id = ?", id);
    }

    /** 恢复（enabled=1）。 */
    public void enable(long id) {
        jdbcTemplate.update(
                "UPDATE sensitive_word SET enabled = 1, revision = revision + 1 WHERE id = ?", id);
    }

    /** 硬删除（不可恢复，骨架阶段不开放给管理后台；如需可在 controller 用 @RequirePermission 限定 ADMIN）。 */
    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM sensitive_word WHERE id = ?", id);
    }

    /** 变更指纹（行数 + 最大更新时间）。 */
    public record CheckSum(long count, Instant maxUpdatedAt) {
    }
}
