package com.turbofeed.gateway.service.like;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 收藏持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code media_favorite} 表分片键为 {@code post_id}（与公域时间线幂等键
 * {@code timelineKey} 口径一致），{@code hashMod128} 与 {@code media_like} 同策略，保证「读某条
 * 内容下所有收藏」单分片命中。</p>
 *
 * <p><b>id 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}，与
 * {@code MediaLikeJdbcRepository} 同源（worker=1, datacenter=1）。INSERT 始终显式带值。</p>
 */
@Repository
@RequiredArgsConstructor
public class MediaFavoriteJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, post_id, user_id, created_at";

    /** 落库一条收藏（收藏时 INSERT）。 */
    public void insert(MediaFavorite favorite) {
        jdbcTemplate.update(
                "INSERT INTO media_favorite (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                favorite.id(), favorite.postId(), favorite.userId(), Timestamp.from(favorite.createdAt()));
    }

    /** 取消收藏（按 post_id + user_id 删除，带分片键单分片命中）。 */
    public int delete(String postId, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM media_favorite WHERE post_id = ? AND user_id = ?",
                postId, userId);
    }

    /**
     * 查某内容下全部收藏用户（灾备重建用，带分片键 {@code post_id} 单分片命中）。
     *
     * <p>本表是「Redis 为权威、DB 为灾备」中的灾备侧：{@code MediaFavoriteService#rebuildFromDb}
     * 据此恢复 64 槽去重集合与计数 Key。改造前本表只写不读，灾备承诺无法执行。</p>
     */
    public List<Long> listUserIds(String postId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM media_favorite WHERE post_id = ?", Long.class, postId);
    }

    /**
     * 查某用户收藏的全部内容（「我的收藏」列表灾备重建用，带分片键 {@code post_id}）。
     *
     * <p>Redis 侧 per-user ZSET {@code tf:favorite:user:{userId}} 是「我的收藏」读源；本方法
     * 供该 ZSET 因 TTL 回收或 Redis 重建后用 DB 兜底恢复，使「我的收藏」列表不丢。</p>
     */
    public List<String> listPostIds(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT post_id FROM media_favorite WHERE user_id = ? ORDER BY created_at DESC",
                String.class, userId);
    }

    /**
     * 某用户是否已收藏过某内容（去重集合 TTL 回收后的兜底校验，带分片键单分片命中）。
     *
     * <p><b>为什么需要它</b>：去重集合是「Redis 为权威」的幂等保证，但它有 TTL
     * （{@code MediaFavoriteService#SET_TTL}）。连续 N 天无人访问的内容，64 槽集合会被回收；
     * 此后曾收藏的用户再点一次，{@code SADD} 会返回 1 而被误判为「新收藏」→ 计数 +1，
     * <b>计数虚增且收藏态失真</b>。本方法用持久化的灾备表确认「此前到底收藏没收藏」，
     * 让去重在集合回收后仍有据可依。</p>
     *
     * <p><b>口径：只用于「拦截」，不用于「放行」</b>。本表由收藏主链路<b>异步</b>写入
     * （fail-open，落库失败仅告警），因此「查不到」<b>不能</b>断言用户没收藏过——只能按新收藏放行
     * （即回到改造前的行为）。所以这条兜底是<b>单调改善</b>：能拦住多少算多少，拦不住也不会
     * 比现在更差。</p>
     */
    public boolean existsFavorite(String postId, long userId) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM media_favorite WHERE post_id = ? AND user_id = ?",
                    Integer.class, postId, userId);
            return n != null && n > 0;
        } catch (Exception e) {
            // fail-open：灾备表不可用时按「没收藏过」放行（= 改造前行为），绝不因兜底查询失败而拒绝收藏
            return false;
        }
    }
}
