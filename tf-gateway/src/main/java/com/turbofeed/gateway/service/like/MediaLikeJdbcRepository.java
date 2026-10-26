package com.turbofeed.gateway.service.like;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 点赞持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code media_like} 表分片键为 {@code post_id}（与公域时间线幂等键
 * {@code timelineKey} 口径一致），{@code hashMod4} 与 {@code comment} 表同策略，保证「读某条
 * 内容下所有点赞」单分片命中。</p>
 *
 * <p><b>id 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}，与
 * {@code CommentJdbcRepository} 同源（worker=1, datacenter=1）。INSERT 始终显式带值。</p>
 */
@Repository
@RequiredArgsConstructor
public class MediaLikeJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, post_id, user_id, created_at";

    /** 落库一条点赞（赞时 INSERT）。 */
    public void insert(MediaLike like) {
        jdbcTemplate.update(
                "INSERT INTO media_like (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                like.id(), like.postId(), like.userId(), Timestamp.from(like.createdAt()));
    }

    /** 取消点赞（按 post_id + user_id 删除，带分片键单分片命中）。 */
    public int delete(String postId, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM media_like WHERE post_id = ? AND user_id = ?",
                postId, userId);
    }

    /**
     * 查某内容下全部点赞用户（A4 灾备重建用，带分片键 {@code post_id} 单分片命中）。
     *
     * <p>本表是「Redis 为权威、DB 为灾备」中的灾备侧：{@code MediaLikeService#rebuildFromDb}
     * 据此恢复 64 槽去重集合与计数 Key。改造前本表只写不读，灾备承诺无法执行。</p>
     */
    public List<Long> listUserIds(String postId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM media_like WHERE post_id = ?", Long.class, postId);
    }

    /**
     * 某用户是否已赞过某内容（去重集合 TTL 回收后的兜底校验，带分片键单分片命中）。
     *
     * <p><b>为什么需要它</b>：去重集合是「Redis 为权威」的幂等保证，但它有 TTL
     * （{@code MediaLikeService#SET_TTL}）。连续 N 天无人访问的内容，64 槽集合会被回收；
     * 此后曾点赞的用户再点一次，{@code SADD} 会返回 1 而被误判为「新赞」→ 计数 +1，
     * <b>计数虚增且红心态失真</b>。本方法用持久化的灾备表确认「此前到底赞没赞过」，
     * 让去重在集合回收后仍有据可依。</p>
     *
     * <p><b>口径：只用于「拦截」，不用于「放行」</b>。本表由点赞主链路<b>异步</b>写入
     * （fail-open，落库失败仅告警），因此「查不到」<b>不能</b>断言用户没赞过——只能按新赞放行
     * （即回到改造前的行为）。所以这条兜底是<b>单调改善</b>：能拦住多少算多少，拦不住也不会
     * 比现在更差。</p>
     */
    public boolean existsLike(String postId, long userId) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM media_like WHERE post_id = ? AND user_id = ?",
                    Integer.class, postId, userId);
            return n != null && n > 0;
        } catch (Exception e) {
            // fail-open：灾备表不可用时按「没赞过」放行（= 改造前行为），绝不因兜底查询失败而拒绝点赞
            return false;
        }
    }
}
