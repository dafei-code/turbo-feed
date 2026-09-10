package com.turbofeed.gateway.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 作者申诉仓储（appeal 表，单表，ds_0；MVP 简化，按 media_id 索引）。
 *
 * <p>申诉闭环：作者对自身被驳回/下架内容申诉 → 状态翻 APPEALING 并写本表（status=0 待复核）；
 * 管理员在 {@code /api/admin/media/appeal-review} 复核（翻案→APPROVED 恢复公域+信用加回，
 * 维持→维持原拒绝/下架态）。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AppealRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 写入一条申诉（status=0 待复核）。 */
    public void insert(String mediaId, long authorUserId) {
        jdbcTemplate.update(
                "INSERT INTO appeal (media_id, author_user_id, status, created_at) "
                        + "VALUES (?, ?, 0, ?)",
                mediaId, authorUserId, Timestamp.from(Instant.now()));
    }

    /** 处理完标记（upheld=true 翻案 status=1 / false 维持 status=2）。 */
    public void resolve(String mediaId, boolean upheld) {
        jdbcTemplate.update(
                "UPDATE appeal SET status = ? WHERE media_id = ? AND status = 0",
                upheld ? 1 : 2, mediaId);
    }
}
