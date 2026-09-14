package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体元数据持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p>承载此前 {@code MediaReviewService.statusRegistry} 与 {@code MediaQueryService.userIndex}
 * 两个内存态的全部职责，使 media 元数据在重启后不丢失。</p>
 *
 * <p><b>分片路由约束</b>：media 表分片键为 {@code user_id}。所有写/读均显式携带
 * {@code user_id}，确保 ShardingSphere 精准命中单分片、不发生全分片广播：
 * <ul>
 *   <li>{@link #insert} / {@link #updateStatusCas} / {@link #listByUser} 天然带 user_id；</li>
 *   <li>{@link #getStatus} 以 (media_id, user_id) 为条件，同样精准——单凭 media_id 会广播，
 *   故调用方必须传入 user_id（来自 JWT，非客户端可控）。</li>
 *   <li>{@link #listPostImages} 以 (user_id, post_id) 为条件。<b>post_id 不是分片键</b>，
 *   仅凭 post_id 查询同样会广播，必须同时带 user_id。</li>
 * </ul>
 * </p>
 *
 * <p><b>幂等</b>：{@link #insert} 用 {@code ON DUPLICATE KEY UPDATE}（media_id 为主键），
 * 事件重投（RocketMQ 重试）不会插入重复行，仅刷新 status/url。</p>
 *
 * <p><b>status 映射</b>：TINYINT ↔ {@link MediaStatus}，0=PENDING 1=APPROVED 2=REJECTED
 * 3=DELETED 4=TAKEN_DOWN 5=APPEALING。</p>
 *
 * <h3>「一帖多图」（抖音式图文）的存储模型</h3>
 * <p>一次上传批次的 N 张图 = N 条 media 行，共享同一个 {@code post_id}，{@code seq} 为 0 起的
 * 帖内序号（轮播顺序）。<b>分片键仍是 user_id</b>，因此「同一帖的 N 张图必然落在同一分片」——
 * 帖内查询永远单分片命中，不广播。</p>
 *
 * <p><b>历史数据（post_id = ''）</b>：视为「单图帖」，帖子身份回退用 {@code media_id}。
 * 所有聚合查询因此统一用 {@code seq = 0} 取帖代表行（历史行的 seq 取默认值 0），
 * 无需数据回填、新旧数据走同一段代码。</p>
 *
 * <p><b>帖级聚合查询为什么是两条 SQL</b>：先按 {@code seq = 0} 取每帖的代表行并分页
 * （一条帖只出一行，分页语义天然正确），再按这批 post_id 一次性取回帖内全部图片。
 * 这样避免了 {@code GROUP_CONCAT} 的长度截断风险，也避免了「先拉全表再在内存里分组」
 * 的分页错位与内存放大。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MediaJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 帖代表行的判定条件：帖内序号为 0 的行（历史单图行取默认值 0，同样命中）。 */
    private static final String REPRESENTATIVE_CONDITION = "seq = 0";

    private static final String ROW_COLUMNS =
            "post_id, media_id, url, seq, status, created_at, caption, caption_mark";

    private static final RowMapper<MediaRow> ROW_MAPPER = (rs, rowNum) -> new MediaRow(
            rs.getString("post_id"),
            rs.getString("media_id"),
            rs.getString("url"),
            rs.getInt("seq"),
            toStatus(rs.getInt("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("caption"),
            rs.getString("caption_mark"));

    /**
     * 行级读取结果（一条 media 记录）。
     *
     * <p>{@link #key()} 是帖身份的回退实现：有 {@code post_id} 用 post_id，历史数据回退
     * {@code media_id}。仓储层的聚合与 {@code FeedItemView#timelineKey()} 保持同一口径，
     * 否则「我的内容」与「公域发现流」会把同一帖当成两帖。</p>
     */
    private record MediaRow(String postId, String mediaId, String url, int seq, MediaStatus status,
                            Instant createdAt, String caption, String captionMark) {

        String key() {
            return postId == null || postId.isBlank() ? mediaId : postId;
        }

        MediaItem toItem() {
            // 行级视图：images 仅含自身（真正的帖内图片由聚合阶段补齐）
            return new MediaItem(postId, mediaId, url, List.of(url), seq, status, createdAt, caption, captionMark);
        }
    }

    /**
     * 落库一条媒体记录。{@code media_id} 为主键，重投天然幂等。
     *
     * <p><b>重复键为何不回写 caption</b>：描述/标题的<b>初始写入方</b>是上传服务
     * （{@code MediaUploadService.storeOne}），<b>修改方</b>只有 {@link #updateCaption}。
     * 审核兜底插入（{@code MediaReviewService.handleUploaded}）只负责把受理态落库，
     * 若在此处回写 caption，MQ 异步投递期间用户对描述的编辑会被事件里的旧值覆盖（丢更新）；
     * 故重复键仅刷新 status/url。</p>
     *
     * @param postId 帖子标识（一次上传批次，一帖多图）；历史单图数据传 {@code ""}
     * @param seq    帖内图片序号（0 起，决定轮播顺序）
     */
    public void insert(String postId, String mediaId, long userId, String url, MediaStatus status,
                       String caption, String captionMark, int seq, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO media (post_id, media_id, user_id, url, status, media_type, file_size, "
                        + "caption, caption_mark, seq, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'IMAGE', 0, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), url = VALUES(url)",
                postId == null ? "" : postId,
                mediaId, userId, url, toCode(status),
                caption == null ? "" : caption,
                captionMark == null ? "" : captionMark,
                seq,
                java.sql.Timestamp.from(createdAt));
        log.debug("媒体落库: postId={}, mediaId={}, seq={}, userId={}, status={}",
                postId, mediaId, seq, userId, status);
    }

    /**
     * 更新媒体描述/标题（用户编辑已上传内容的文案）。
     *
     * <p><b>按帖更新</b>：描述是<b>帖子级</b>属性（一次上传共用一个 caption），因此这里会更新
     * 帖内<b>全部</b>行，而不是只改代表行——否则同帖其余行的 caption 会与代表行分叉，
     * 一旦代表行被清理（例如按 media_id 删除后重建）就会读到旧文案。历史单图帖（post_id=''）
     * 自然退化为「只更新自己这一行」。</p>
     */
    public void updateCaption(String mediaId, long userId, String caption, String captionMark) {
        String postId = findPostId(mediaId, userId);
        String c = caption == null ? "" : caption;
        String m = captionMark == null ? "" : captionMark;
        if (postId == null || postId.isBlank()) {
            updatePostRows(
                    "UPDATE media SET caption = ?, caption_mark = ? WHERE media_id = ? AND user_id = ?",
                    c, m, mediaId, userId);
            return;
        }
        updatePostRows(
                "UPDATE media SET caption = ?, caption_mark = ? WHERE user_id = ? AND post_id = ?",
                c, m, userId, postId);
    }

    /**
     * 按帖整批更新审核状态（<b>无 CAS 的盲写入口</b>）。
     *
     * <p><b>为什么必须整帖更新</b>：本项目采用「整帖一审」——一帖的 N 张图同上同下，
     * 状态语义才一致。若只改代表行，「我的内容」按任一非代表行读状态、公域时间线按下架
     * 时读到的行状态可能互相矛盾，出现「已下架但部分图仍可见」的内容安全问题。</p>
     *
     * <p><b>使用边界</b>：本方法<b>不做前置状态校验</b>，是盲写，只适用于状态机之外的一致性
     * 修复/补偿（如运维脚本纠正脏状态）。<b>审核状态流转一律走 {@link #updateStatusCas}</b>——
     * 那才是把「并发双写」收敛为「一次生效 + 一次幂等返回」的正确入口；用本方法做流转会重新
     * 打开检查-后-执行的窗口，让整帖多行 UPDATE 的 InnoDB 死锁复现（取证见 2026-09-14）。</p>
     */
    public void updateStatusByPost(long userId, String postId, MediaStatus status) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        int n = updatePostRows(
                "UPDATE media SET status = ? WHERE user_id = ? AND post_id = ?",
                toCode(status), userId, postId);
        log.debug("整帖状态流转: postId={}, userId={}, status={}, rows={}", postId, userId, status, n);
    }

    /**
     * 整帖状态流转的 CAS（compare-and-set）：仅当帖内存在<b>期望前置状态</b>的行时才更新，
     * 并返回实际影响行数。
     *
     * <p><b>为什么需要 CAS</b>：{@code MediaReviewService#review} 是「先 {@code getStatus} 读、
     * 再写状态」的<b>检查-后-执行</b>，本身没有互斥。同一帖可能被两条路径同时审核：
     * ① 上传后的异步「先发后审」——{@code AccountCreditService} 对无信用记录的账号默认 L1，
     * 故 {@code handleUploaded} 会直接置 APPROVED；② 管理员人工审核 {@code reviewByMediaId}。
     * 两者若都在对方提交前读到 PENDING，就会各自发起一次整帖 UPDATE，形成同帖双写。
     * 带上 {@code status = 期望前置态} 后，只有一方能命中（另一方得到 0 行），
     * 把「并发双写」收敛为「一次生效 + 一次幂等返回」，同时收窄了加锁范围。</p>
     *
     * @param expected 期望的前置状态（调用方刚读到的状态）
     * @param target   目标状态
     * @return 实际更新的行数；0 表示帖已不在期望状态（已被其他路径流转）
     */
    public int updateStatusCas(String mediaId, long userId, MediaStatus expected, MediaStatus target) {
        String postId = findPostId(mediaId, userId);
        if (postId == null || postId.isBlank()) {
            return updatePostRows(
                    "UPDATE media SET status = ? WHERE media_id = ? AND user_id = ? AND status = ?",
                    toCode(target), mediaId, userId, toCode(expected));
        }
        return updatePostRows(
                "UPDATE media SET status = ? WHERE user_id = ? AND post_id = ? AND status = ?",
                toCode(target), userId, postId, toCode(expected));
    }

    /**
     * 整帖多行 UPDATE 的统一执行入口（带<b>有界重试</b>）。
     *
     * <p><b>为什么需要重试</b>：整帖更新会命中帖内全部行（1..9 行），WHERE 条件走
     * {@code idx_user_post(user_id, post_id, seq)} 这个二级索引，MySQL 需要在二级索引与聚簇主键
     * 之间往返加锁。同帖的两条并发整帖 UPDATE 加锁顺序可能相反，形成
     * 「一个持 idx_user_post 等 PRIMARY、另一个持 PRIMARY 等 idx_user_post」的互等，
     * InnoDB 判定为死锁并回滚其中一方。取证见 2026-09-14
     * {@code SHOW ENGINE INNODB STATUS → LATEST DETECTED DEADLOCK}：两个事务执行的是
     * 完全相同的整帖 UPDATE，互相等待。</p>
     *
     * <p>MySQL 对该错误的官方处置就是 "try restarting transaction"：单条 UPDATE 在死锁时
     * <b>整条回滚</b>、不会留下半更新，因此重试是安全且幂等的。重试次数与退避都取小值
     * （总量 &lt; 100ms），避免在真实高争用下把请求线程拖长；耗尽后原样抛出，
     * 由上层返回 50000 —— 不做无界重试去掩盖问题。</p>
     */
    private int updatePostRows(String sql, Object... args) {
        final int maxAttempts = 3;
        final long backoffMillis = 15L;
        for (int attempt = 1; ; attempt++) {
            try {
                return jdbcTemplate.update(sql, args);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.error("整帖更新连续 {} 次遇锁冲突，放弃重试: sql={}", attempt, sql, e);
                    throw e;
                }
                log.warn("整帖更新遇锁冲突（第 {}/{} 次尝试），退避后重试: {}",
                        attempt, maxAttempts, e.getMessage());
                sleepQuietly(backoffMillis * attempt);
            }
        }
    }

    /** 有界重试的退避睡眠；被中断时恢复中断位并立即返回，交由上层处理。 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 查询某用户上传的<b>帖子</b>列表（按上传时间倒序，分页）。
     *
     * <p>一条帖子只返回一条记录，{@code images} 为该帖全部图片（按 seq 升序）。
     * 分页发生在<b>代表行</b>上（{@code seq = 0}），因此一帖只占一个分页位，
     * 不会出现「9 张图吃掉 9 个位置」的问题。</p>
     *
     * @param statusFilter 状态过滤（null = 不过滤，供个人中心全量/统计使用）
     * @param limit        单页帖子数（≤0 由调用方兜底为默认值）
     * @param offset       偏移量（从 0 开始，单位是<b>帖子</b>）
     */
    public List<MediaItem> listByUser(long userId, MediaStatus statusFilter, int limit, long offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ROW_COLUMNS + " FROM media WHERE user_id = ? AND " + REPRESENTATIVE_CONDITION);
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
        return toPosts(jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray()), userId);
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
     * <p>返回的是<b>帖子</b>（一帖一条，images 为帖内全部图片）。本方法当前只在
     * {@code turbofeed.feed.degraded-mode=local-scan}（引擎不可用时的本地/演示降级）下被调用。</p>
     *
     * @param limit  单页帖子数
     * @param offset 偏移量（单位是帖子）
     */
    public List<MediaItem> listApprovedGlobal(int limit, long offset) {
        List<MediaRow> rows = jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM media WHERE status = ? AND " + REPRESENTATIVE_CONDITION
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, toCode(MediaStatus.APPROVED), limit, offset);
        return toPosts(rows, null);
    }

    /**
     * 人工审核队列查询：跨分片广播查全平台 PENDING 内容（按受理时间倒序，分页）。
     *
     * <p><b>重要</b>：与 {@link #listApprovedGlobal} 同源——不携带分片键，ShardingSphere 广播到全部
     * 分片并合并，仅适用于演示/小数据量。生产环境人工审核队列应由审核中台 + 异构索引提供，
     * 不可实时扫分片库（海量 PENDING 下广播查询会拖垮所有分片）。</p>
     *
     * <p><b>整帖一审的可见性</b>：审核员必须看到帖内全部图片才能对整帖下判断，因此返回的
     * {@code images} 是完整图片列表（<b>含逐张补齐的分片广播查询</b>）——这是审核正确性的必要成本，
     * 也是它只能在演示/小数据量下使用的原因之一。</p>
     *
     * @param limit  单页帖子数
     * @param offset 偏移量（单位是帖子）
     */
    public List<MediaItem> listPendingGlobal(int limit, long offset) {
        List<MediaRow> rows = jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM media WHERE status = ? AND " + REPRESENTATIVE_CONDITION
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, toCode(MediaStatus.PENDING), limit, offset);
        return toPosts(rows, null);
    }

    /**
     * 按 (media_id, user_id) 精确取单条<b>行</b>（审核通过后补写公域时间线用）。
     *
     * <p>返回行级视图：{@code postId} / {@code seq} 为真实值，{@code images} 只含自身。
     * 需要整帖图片时改用 {@link #listPostImages}。</p>
     *
     * @return 命中的媒体行，未命中返回 {@code null}
     */
    public MediaItem findMedia(String mediaId, long userId) {
        List<MediaRow> rows = jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM media WHERE media_id = ? AND user_id = ?",
                ROW_MAPPER, mediaId, userId);
        return rows.isEmpty() ? null : rows.get(0).toItem();
    }

    /**
     * 取某帖全部图片（按 seq 升序），用于「审核通过 → 整帖入流」与「整帖删除」。
     *
     * <p>带 {@code user_id} 分片键，单分片精准命中。</p>
     *
     * @return 帖内全部行；帖不存在或只传了空 postId 时返回空列表
     */
    public List<MediaItem> listPostImages(long userId, String postId) {
        if (postId == null || postId.isBlank()) {
            return List.of();
        }
        List<MediaRow> rows = jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM media WHERE user_id = ? AND post_id = ? ORDER BY seq ASC",
                ROW_MAPPER, userId, postId);
        List<MediaItem> items = new ArrayList<>(rows.size());
        for (MediaRow r : rows) {
            items.add(r.toItem());
        }
        return items;
    }

    /**
     * 查询 media_id 归属的帖子标识。
     *
     * <p>带 {@code user_id} 分片键精准路由。返回 {@code null} 表示该行不存在；
     * 返回空串表示命中历史单图帖（{@code post_id = ''}）——两者语义不同，调用方需分别处理。</p>
     */
    public String findPostId(String mediaId, long userId) {
        List<String> postIds = jdbcTemplate.query(
                "SELECT post_id FROM media WHERE media_id = ? AND user_id = ?",
                (rs, rn) -> rs.getString("post_id"), mediaId, userId);
        return postIds.isEmpty() ? null : postIds.get(0);
    }

    /**
     * 查询单条内容状态。带 user_id 分片键精准路由；未查到返回 {@code null}
     * （调用方按 PENDING 处理，对应「已受理但审核事件未到」的中间态）。
     *
     * <p>整帖一审下同帖各行状态一致，取任意行等价。</p>
     */
    public MediaStatus getStatus(String mediaId, long userId) {
        List<Integer> codes = jdbcTemplate.query(
                "SELECT status FROM media WHERE media_id = ? AND user_id = ?",
                (rs, rn) -> rs.getInt("status"), mediaId, userId);
        return codes.isEmpty() ? null : toStatus(codes.get(0));
    }

    /**
     * 逻辑删除某个<b>帖子</b>（用户主动删除自己的内容）：将帖内全部行状态置 {@link MediaStatus#DELETED}。
     *
     * <p><b>为什么按帖删</b>：一帖多图下按单行逻辑删除会留下「孤儿图」——其余行仍是 APPROVED，
     * 公域时间线若按行物化就会继续可见。整帖一起置删是唯一自洽的语义。</p>
     *
     * <p>物理对象已由存储实现（MinIO 等）删除，DB 仅标记删除态，保留审计痕迹。
     * 带 {@code user_id} 分片键，仅删除「本人」内容——即使传入他人 mediaId，
     * 因 (media_id, user_id) 不匹配也不会误删。</p>
     *
     * @param mediaId 内容唯一标识（帖代表行；其 post_id 决定删除范围）
     * @param userId  归属用户（分片键，来自 JWT，防越权删他人）
     */
    public void delete(String mediaId, long userId) {
        String postId = findPostId(mediaId, userId);
        if (postId == null || postId.isBlank()) {
            updatePostRows(
                    "UPDATE media SET status = ? WHERE media_id = ? AND user_id = ?",
                    toCode(MediaStatus.DELETED), mediaId, userId);
        } else {
            updatePostRows(
                    "UPDATE media SET status = ? WHERE user_id = ? AND post_id = ?",
                    toCode(MediaStatus.DELETED), userId, postId);
        }
        log.debug("帖子逻辑删除: postId={}, mediaId={}, userId={}", postId, mediaId, userId);
    }

    /**
     * 物理删除某个帖子的全部行（<b>仅用于上传中途失败的补偿清理</b>）。
     *
     * <p>用户删除内容走 {@link #delete}（逻辑删除、保留审计）。本方法只服务于一个场景：
     * 一次上传的 N 张图在存储中途失败时，把此前已落库的残行删干净，避免留下
     * 「缺 seq=0 代表行 / 张数不齐」的半成品帖——这种帖子既不会出现在「我的内容」
     * （代表行不存在），也无法被用户删除，是最难排查的一类脏数据。</p>
     *
     * @return 实际删除的行数
     */
    public int hardDeleteByPost(long userId, String postId) {
        if (postId == null || postId.isBlank()) {
            return 0;
        }
        int n = updatePostRows("DELETE FROM media WHERE user_id = ? AND post_id = ?", userId, postId);
        log.warn("补偿清理：物理删除残行 postId={}, userId={}, rows={}", postId, userId, n);
        return n;
    }

    /**
     * 把代表行聚合为「帖子」列表，并按需补齐帖内全部图片。
     *
     * <p><b>图片补齐的两次查询策略</b>：
     * <ul>
     *   <li>{@code userId != null}（「我的内容」路径）：带分片键一次查回这批帖的全部图片，
     *   单分片精准命中；</li>
     *   <li>{@code userId == null}（公域/审核队列等广播路径）：按 post_id 集合广播查询补齐
     *   —— 这些路径本就是跨分片占位实现，见各方法 javadoc 的说明。</li>
     * </ul>
     * 历史单图行（post_id = ''）不参与补齐，直接用它自己的 url 作为单元素图片列表。</p>
     */
    private List<MediaItem> toPosts(List<MediaRow> representatives, Long userId) {
        if (representatives.isEmpty()) {
            return List.of();
        }
        List<String> postIds = collectPostIds(representatives, userId);
        Map<String, List<String>> imagesByPost = postIds.isEmpty()
                ? Map.of()
                : fetchImages(postIds, userId);

        List<MediaItem> posts = new ArrayList<>(representatives.size());
        for (MediaRow r : representatives) {
            List<String> images = imagesByPost.get(r.key());
            if (images == null || images.isEmpty()) {
                images = r.url() == null ? List.of() : List.of(r.url());
            }
            posts.add(new MediaItem(r.postId(), r.mediaId(), r.url(), images, r.seq(),
                    r.status(), r.createdAt(), r.caption(), r.captionMark()));
        }
        return posts;
    }

    /**
     * 收集需要补齐图片的真实 post_id 集合。
     *
     * <p>只收「非空 post_id」——历史单图行的 post_id 是空串，把它们混进 {@code IN (...)}
     * 会把该用户<b>全部历史内容</b>都查出来（空串是同一个值），既错又慢。
     * 广播路径（{@code userId == null}）无法按 user_id 收窄，更需要这层过滤。</p>
     */
    private static List<String> collectPostIds(List<MediaRow> representatives, Long userId) {
        Set<String> ids = new LinkedHashSet<>();
        for (MediaRow r : representatives) {
            if (r.postId() != null && !r.postId().isBlank()) {
                ids.add(r.postId());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        if (userId != null) {
            return new ArrayList<>(ids);
        }
        // 广播路径：无法靠 user_id 收窄，限制补齐规模，避免一次拉爆全库
        List<String> limited = new ArrayList<>(ids);
        return limited.size() > 200 ? limited.subList(0, 200) : limited;
    }

    /** 按 post_id 集合取回帖内全部图片 URL（按 seq 升序），结果为 postKey → images。 */
    private Map<String, List<String>> fetchImages(List<String> postIds, Long userId) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < postIds.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        List<Object> args = new ArrayList<>();
        String sql;
        if (userId != null) {
            sql = "SELECT post_id, url FROM media WHERE user_id = ? AND post_id IN (" + in + ") "
                    + "ORDER BY post_id, seq ASC";
            args.add(userId);
        } else {
            sql = "SELECT post_id, url FROM media WHERE post_id IN (" + in + ") "
                    + "ORDER BY post_id, seq ASC";
        }
        args.addAll(postIds);

        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String postId = rs.getString("post_id");
            String url = rs.getString("url");
            if (postId != null && !postId.isBlank() && url != null) {
                result.computeIfAbsent(postId, k -> new ArrayList<>()).add(url);
            }
        }, args.toArray());
        return result;
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
