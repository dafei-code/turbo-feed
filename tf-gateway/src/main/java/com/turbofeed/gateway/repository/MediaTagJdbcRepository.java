package com.turbofeed.gateway.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容标签持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p>承载 caption {@code #话题} 解析标签的「可重建冷存」。本表与 Redis
 * {@code tf:tag:media / tf:media:tags} 同源——Redis 是<b>读路径主索引</b>（推荐个性化召回
 * 直接查 Redis），本表只是<b>系统级冷备份/可重建记录</b>：一旦 Redis 数据因重启/迁移需要重建，
 * 可由本表或事件回流重新灌入。因此本仓储的所有写都必须<b>失败不阻塞主链路</b>
 * （见下方各方法 javadoc 的 best-effort 约定）。</p>
 *
 * <h3>分片路由约束</h3>
 * <p>逻辑表 {@code media_tag} 分片键为 {@code post_id}（= 引擎 {@code timelineKey}），
 * 算法 {@code hashMod128}；偶数下标落 {@code turbo_feed_1}(ds_0)、奇数下标落
 * {@code turbo_feed_2}(ds_1)，与 {@code media_0..127} 完全对齐（DDL 见
 * {@code deploy/mysql/alter_media_tag.sql}）。所有方法都显式携带 {@code post_id}，
 * 单分片精准命中、不发生全分片广播。</p>
 *
 * <h3>与 media 表的差异</h3>
 * <ul>
 *   <li>media 表分片键是 {@code user_id}；本表分片键是 {@code post_id}——
 *   同一帖的 N 张图的标签只落在<b>一条</b> {@code media_tag} 记录（按 post_id 聚合），
 *   不随图片张数膨胀。</li>
 *   <li>media 表是内容事实源（丢失即丢内容）；本表是<b>派生可重建</b>的，
 *   故写入采用 best-effort（见上）。</li>
 * </ul>
 *
 * <h3>MVP 权重约定</h3>
 * <p>本表 {@code weight} 统一落 {@code 1.0}。真实的内容级标签权重并不在本表累积——
 * 用户兴趣的<b>加权累积</b>发生在 Redis {@code tf:user:interest}（见
 * {@code InterestService}）。本表的 weight 列仅为将来「内容热度/标签聚合」预留，
 * MVP 阶段不参与任何排序计算。</p>
 */
@Repository
public class MediaTagJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(MediaTagJdbcRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public MediaTagJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入某帖的标签集合（set 语义：先清后插，幂等可重放）。
     *
     * <p><b>为什么先删后插</b>：caption 编辑会使标签集合变化，本表需反映「当前标签」而非
     * 历史并集。删除与插入都按 {@code post_id} 分片键命中同一分片，无广播。</p>
     *
     * <p><b>best-effort</b>：本方法在 {@code MediaReviewService#publishAppend} 的事务内被调用，
     * 但 media_tag 是可重建冷存、不是内容事实源。若 DB 写入异常，本方法<b>捕获并仅 warn</b>，
     * 绝不向上抛出——避免一次冷存写入失败把整条「过审→入公域时间线」的发布链路 rollback
     * （那会让用户内容无法公开，远比丢一份可重建标签严重）。标签缺失后续可由 Redis/事件回流补齐。</p>
     *
     * @param postId 帖子标识（timelineKey；有 post_id 用 post_id，历史单图回退 media_id）
     * @param tags   caption 解析出的标签（小写、去重、有序；空集合表示清空该帖标签）
     */
    public void save(String postId, List<String> tags) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            // 先清后插：保证「当前标签集合」语义，避免历史标签残留
            jdbcTemplate.update("DELETE FROM media_tag WHERE post_id = ?", postId);
            if (tags == null || tags.isEmpty()) {
                return;
            }
            String sql = "INSERT INTO media_tag (post_id, tag, weight, created_at) "
                    + "VALUES (?, ?, 1.0, ?) "
                    + "ON DUPLICATE KEY UPDATE weight = weight";
            List<Object[]> batch = new ArrayList<>(tags.size());
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                batch.add(new Object[]{postId, tag, now});
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
            log.debug("内容标签落库: postId={}, tags={}", postId, tags);
        } catch (RuntimeException ex) {
            // best-effort：冷存写入失败不阻断主链路（见 javadoc）
            log.warn("内容标签冷存写入失败（可重建，忽略）: postId={}, cause={}",
                    postId, ex.getMessage());
        }
    }

    /**
     * 摘除某帖的全部标签（内容下架/删除时调用）。
     *
     * <p>按 {@code post_id} 分片键单分片命中。同样 best-effort：下架是「安全动作」，
     * 冷存标签清理失败不影响内容已在 DB 下架这一事实；标签残留最多是重建时多灌一条，
     * 不构成内容安全问题。</p>
     *
     * @param postId 帖子标识（与 {@link #save} 同键口径，否则新旧标签会落在两个索引）
     */
    public void remove(String postId) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            int n = jdbcTemplate.update("DELETE FROM media_tag WHERE post_id = ?", postId);
            log.debug("内容标签摘除: postId={}, rows={}", postId, n);
        } catch (RuntimeException ex) {
            log.warn("内容标签冷存清理失败（可重建，忽略）: postId={}, cause={}",
                    postId, ex.getMessage());
        }
    }

    /**
     * 读取某帖的全部标签（单分片精准查询）。
     *
     * <p>本方法主要用于运维/重建/校验，<b>不进入推荐读路径</b>——推荐召回读 Redis
     * {@code tf:media:tags}。返回值可能为 null（帖无标签）或空列表。</p>
     */
    public List<String> tagsOf(String postId) {
        if (postId == null || postId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT tag FROM media_tag WHERE post_id = ? ORDER BY created_at ASC, tag ASC",
                (rs, rn) -> rs.getString("tag"),
                postId);
    }
}
