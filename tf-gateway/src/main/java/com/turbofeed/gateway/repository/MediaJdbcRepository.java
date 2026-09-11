package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体元数据持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p>承载此前 {@code MediaReviewService.statusRegistry} 与 {@code MediaQueryService.userIndex}
 * 两个内存态的全部职责，使 media 元数据在重启后不丢失。</p>
 *
 * <p><b>分片路由约束</b>：media 表分片键为 {@code user_id}。所有写/读均显式携带
 * {@code user_id}，确保 ShardingSphere 精准命中单分片、不发生全分片广播：
 * <ul>
 *   <li>{@link #insert} / {@link #updateStatus} / {@link #listByUser} 天然带 user_id；</li>
 *   <li>{@link #getStatus} 以 (media_id, user_id) 为条件，同样精准——单凭 media_id 会广播，
 *   故调用方必须传入 user_id（来自 JWT，非客户端可控）。</li>
 * </ul>
 * </p>
 *
 * <p><b>幂等</b>：{@link #insert} 用 {@code ON DUPLICATE KEY UPDATE}（media_id 为主键），
 * 事件重投（RocketMQ 重试）不会插入重复行，仅刷新 status/url。</p>
 *
 * <p><b>status 映射</b>：TINYINT ↔ {@link MediaStatus}，0=PENDING 1=APPROVED 2=REJECTED。</p>
 *
 * <p><b>P1.5 查询优化</b>：{@link #listByUser} 增加 {@code status} 过滤与 {@code LIMIT/OFFSET}
 * 分页，避免一次性回吐用户全部内容、也避免前端在内存里过滤状态；{@link #listApprovedGlobal}
 * 为公域推荐流的<b>占位</b>实现，跨分片广播查全平台 APPROVED，仅适用于演示/小数据量，
 * 生产须由推荐服务 + 异构索引取代（见 changelog 0017）。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MediaJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<MediaItem> MEDIA_ROW_MAPPER = (rs, rowNum) -> new MediaItem(
            rs.getString("media_id"),
            rs.getString("url"),
            toStatus(rs.getInt("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("caption"),
            rs.getString("caption_mark"));

    /** 落库一条媒体记录（受理态）。media_id 为主键，重投天然幂等。 */
    public void insert(String mediaId, long userId, String url, MediaStatus status,
                       String caption, String captionMark, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO media (media_id, user_id, url, status, media_type, file_size, caption, caption_mark, created_at) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 0, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), url = VALUES(url), "
                        + "caption = VALUES(caption), caption_mark = VALUES(caption_mark)",
                mediaId, userId, url, toCode(status),
                caption == null ? "" : caption,
                captionMark == null ? "" : captionMark,
                java.sql.Timestamp.from(createdAt));
        log.debug("媒体落库: mediaId={}, userId={}, status={}", mediaId, userId, status);
    }

    /**
     * 更新媒体描述/标题（用户编辑已上传内容的文案）。
     *
     * <p>带 {@code user_id} 分片键精准路由；空 caption / captionMark 写空串。
     * 该方法不重置 status——描述修改不影响审核流转。</p>
     */
    public void updateCaption(String mediaId, long userId, String caption, String captionMark) {
        jdbcTemplate.update(
                "UPDATE media SET caption = ?, caption_mark = ? WHERE media_id = ? AND user_id = ?",
                caption == null ? "" : caption,
                captionMark == null ? "" : captionMark,
                mediaId, userId);
    }

    /** 审核状态流转（PENDING -> APPROVED/REJECTED）。带 user_id 分片键精准路由。 */
    public void updateStatus(String mediaId, long userId, MediaStatus status) {
        jdbcTemplate.update(
                "UPDATE media SET status = ? WHERE media_id = ? AND user_id = ?",
                toCode(status), mediaId, userId);
    }

    /**
     * 查询某用户上传的内容列表（按上传时间倒序，分页）。带 user_id 分片键，精准命中单分片。
     *
     * @param statusFilter 状态过滤（null = 不过滤，供个人中心全量/统计使用）
     * @param limit        单页条数（≤0 由调用方兜底为默认值）
     * @param offset       偏移量（从 0 开始）
     */
    public List<MediaItem> listByUser(long userId, MediaStatus statusFilter, int limit, long offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT media_id, url, status, created_at, caption, caption_mark FROM media WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (statusFilter != null) {
            sql.append(" AND status = ?");
            args.add(toCode(statusFilter));
        } else {
            // 逻辑删除的内容不展示在「我的内容」：默认过滤 DELETED，避免已删内容重现
            sql.append(" AND status <> ?");
            args.add(toCode(MediaStatus.DELETED));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MEDIA_ROW_MAPPER, args.toArray());
    }

    /**
     * 公域推荐流占位查询：跨分片广播查全平台 APPROVED 内容（按时间倒序，分页）。
     *
     * <p><b>重要</b>：本方法不携带分片键，ShardingSphere 会路由到全部 4 个分片并合并结果，
     * 仅适用于数据量小、演示/占位阶段。生产环境公域推荐流<b>绝不可</b>实时扫分片库——
     * 海量内容下广播查询会拖垮所有分片。必须由推荐服务 + 异构索引
     * （Elasticsearch / 倒排索引 / feed 预生成宽表）提供，见 docs/changelog/0017-feed-pagination-split.md。
     * 缓存层（Redis）也应前置拦截热点 feed，详见 P2 规划。</p>
     *
     * @param limit  单页条数
     * @param offset 偏移量
     */
    public List<MediaItem> listApprovedGlobal(int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT media_id, url, status, created_at, caption, caption_mark FROM media "
                        + "WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MEDIA_ROW_MAPPER, toCode(MediaStatus.APPROVED), limit, offset);
    }

    /**
     * 按 (media_id, user_id) 精确取单条内容（审核通过后补写公域时间线用）。
     *
     * @return 命中的媒体条目（含 url / createdAt），未命中返回 {@code null}
     */
    public MediaItem findMedia(String mediaId, long userId) {
        List<MediaItem> items = jdbcTemplate.query(
                "SELECT media_id, url, status, created_at, caption, caption_mark FROM media WHERE media_id = ? AND user_id = ?",
                MEDIA_ROW_MAPPER, mediaId, userId);
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * 人工审核队列查询：跨分片广播查全平台 PENDING 内容（按受理时间倒序，分页）。
     *
     * <p><b>重要</b>：与 {@link #listApprovedGlobal} 同源——不携带分片键，ShardingSphere 广播到全部
     * 分片并合并，仅适用于演示/小数据量。生产环境人工审核队列应由审核中台 + 异构索引提供，
     * 不可实时扫分片库（海量 PENDING 下广播查询会拖垮所有分片）。</p>
     *
     * @param limit  单页条数
     * @param offset 偏移量
     */
    public List<MediaItem> listPendingGlobal(int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT media_id, url, status, created_at, caption, caption_mark FROM media "
                        + "WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MEDIA_ROW_MAPPER, toCode(MediaStatus.PENDING), limit, offset);
    }

    /**
     * 逻辑删除（用户主动删除自己的内容）：将状态置为 {@link MediaStatus#DELETED}。
     *
     * <p><b>为什么是逻辑删除</b>：物理对象已由存储实现（MinIO 等）删除，DB 仅标记删除态，
     * 保留审计痕迹、避免硬删带来的级联/时序风险。带 {@code user_id} 分片键，
     * 仅删除「本人」内容——即使传入他人 mediaId，因 (media_id, user_id) 不匹配也不会误删。</p>
     *
     * @param mediaId 内容唯一标识
     * @param userId  归属用户（分片键，来自 JWT，防越权删他人）
     */
    public void delete(String mediaId, long userId) {
        jdbcTemplate.update(
                "UPDATE media SET status = ? WHERE media_id = ? AND user_id = ?",
                toCode(MediaStatus.DELETED), mediaId, userId);
        log.debug("媒体逻辑删除: mediaId={}, userId={}", mediaId, userId);
    }

    /**
     * 查询单条内容状态。带 user_id 分片键精准路由；未查到返回 {@code null}
     * （调用方按 PENDING 处理，对应「已受理但审核事件未到」的中间态）。
     */
    public MediaStatus getStatus(String mediaId, long userId) {
        List<Integer> codes = jdbcTemplate.query(
                "SELECT status FROM media WHERE media_id = ? AND user_id = ?",
                (rs, rn) -> rs.getInt("status"), mediaId, userId);
        return codes.isEmpty() ? null : toStatus(codes.get(0));
    }

    private static int toCode(MediaStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case APPROVED -> 1;
            case REJECTED -> 2;
            case DELETED -> 3;
            case TAKEN_DOWN -> 4;
            case APPEALING -> 5;
        };
    }

    private static MediaStatus toStatus(int code) {
        return switch (code) {
            case 1 -> MediaStatus.APPROVED;
            case 2 -> MediaStatus.REJECTED;
            case 3 -> MediaStatus.DELETED;
            case 4 -> MediaStatus.TAKEN_DOWN;
            case 5 -> MediaStatus.APPEALING;
            default -> MediaStatus.PENDING;
        };
    }
}
