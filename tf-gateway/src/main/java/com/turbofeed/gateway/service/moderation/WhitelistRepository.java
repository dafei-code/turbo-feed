package com.turbofeed.gateway.service.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 白名单持久层（JDBC）。
 *
 * <p><b>表路由</b>：{@code sensitive_whitelist} 与 {@code sensitive_word} 同样放
 * {@code turbo_feed_1}（ds_0），并在 shardingsphere-config.yaml 的 {@code !SINGLE}
 * 规则里显式登记（{@code tables: [ds_0.sensitive_whitelist]}）。⚠️ 漏登记的症状是
 * <b>启动即抛 {@code TableNotFoundException}</b>（不是静默降级）——因为
 * {@link WhitelistService} 的启动加载会立刻读这张表。</p>
 *
 * <p><b>排序规则与 {@code sensitive_word} 刻意不同</b>：本表建表语句显式声明
 * {@code COLLATE utf8mb4_bin}（大小写 + 重音敏感）。
 * 原因是本项目已实测出一个坑：{@code sensitive_word.uk_word} 因库级
 * {@code utf8mb4_0900_ai_ci} 而<b>大小写不敏感</b>，加「Abc」后再加「abc」
 * 不新增行（只更新分类），于是「词明明加了却不生效」。
 * 白名单是<b>精确豁免</b>语义，用 {@code utf8mb4_bin} 才与 AC 的逐字符精确匹配一致——
 * 否则「写进白名单的 abc」会意外豁免掉文本里的「ABC」。</p>
 *
 * <p><b>变更检测</b>：{@link #checkSum()} 返回 {@code (COUNT, MAX(updated_at))}，
 * 与 {@link SensitiveWordRepository} 同构——INSERT/UPDATE/DELETE 任一都会改变该二元组，
 * 无需应用层维护计数器。</p>
 */
@Repository
@RequiredArgsConstructor
public class WhitelistRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, word, scene, scope, owner_id, reason, enabled, revision";

    private static final org.springframework.jdbc.core.RowMapper<WhitelistEntry> MAPPER = (rs, rn) ->
            new WhitelistEntry(
                    rs.getLong("id"),
                    rs.getString("word"),
                    rs.getString("scene"),
                    rs.getString("scope"),
                    rs.getLong("owner_id"),
                    rs.getString("reason"),
                    rs.getBoolean("enabled"),
                    rs.getLong("revision"));

    /** 查询所有启用的条目（用于构建内存索引）。 */
    public List<WhitelistEntry> findAllEnabled() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_whitelist WHERE enabled = 1 ORDER BY id",
                MAPPER);
    }

    /** 分页查询全部条目（含禁用），用于后台管理列表。 */
    public List<WhitelistEntry> findAll(int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_whitelist ORDER BY id LIMIT ? OFFSET ?",
                MAPPER, limit, offset);
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensitive_whitelist", Long.class);
        return c == null ? 0L : c;
    }

    /**
     * 变更指纹：{@code (count, maxUpdatedAt)}。空表返回 {@code (0, 1970-01-01)}，与首启动一致。
     */
    public CheckSum checkSum() {
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensitive_whitelist", Long.class);
        Timestamp maxTs = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(updated_at), '1970-01-01 00:00:00') FROM sensitive_whitelist",
                Timestamp.class);
        return new CheckSum(cnt == null ? 0L : cnt,
                maxTs == null ? Instant.EPOCH : maxTs.toInstant());
    }

    /**
     * 新增 / 更新（按 {@code (word, scene, scope, owner_id)} 唯一键 upsert）：
     * 存在则刷新 reason、置 enabled=1、revision+1。返回 upsert 后的行。
     */
    public WhitelistEntry upsert(String word, String scene, String scope, long ownerId, String reason) {
        jdbcTemplate.update(
                "INSERT INTO sensitive_whitelist (word, scene, scope, owner_id, reason, enabled, revision) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1) "
                        + "ON DUPLICATE KEY UPDATE reason = VALUES(reason), enabled = 1, "
                        + "revision = revision + 1",
                word, scene, scope, ownerId, reason);
        List<WhitelistEntry> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sensitive_whitelist "
                        + "WHERE word = ? AND scene = ? AND scope = ? AND owner_id = ?",
                MAPPER, word, scene, scope, ownerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 软删除（enabled=0，保留审计 + 可恢复）。 */
    public void disable(long id) {
        jdbcTemplate.update(
                "UPDATE sensitive_whitelist SET enabled = 0, revision = revision + 1 WHERE id = ?", id);
    }

    /** 恢复（enabled=1）。 */
    public void enable(long id) {
        jdbcTemplate.update(
                "UPDATE sensitive_whitelist SET enabled = 1, revision = revision + 1 WHERE id = ?", id);
    }

    /** 硬删除（不可恢复；仅 ADMIN）。 */
    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM sensitive_whitelist WHERE id = ?", id);
    }

    /** 变更指纹（行数 + 最大更新时间）。 */
    public record CheckSum(long count, Instant maxUpdatedAt) {
    }
}
