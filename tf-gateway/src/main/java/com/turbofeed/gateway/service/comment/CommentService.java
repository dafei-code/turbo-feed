package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.review.ReviewTask;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;

/**
 * 评论服务：发布 + 列表（顶层/楼中楼，keyset 游标） + 点赞（Redis 64 槽）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>内容安全前置</b>：统一走 {@link ContentSecurityService#requireClean}（场景
 *       {@link ContentScene#COMMENT}），一次调用同时完成长度上限与敏感词检测。</li>
 *   <li><b>楼中楼 rootId 派生</b>：回复的 {@code rootId} = 父评论的根评论 ID；父为顶层时取父的
 *       commentId 作为根。父查询带 {@code media_id} 分片键单分片命中，不广播。</li>
 *   <li><b>深分页 = keyset 游标</b>：列表改 {@code (created_at, comment_id)} 范围查询（见
 *       {@link CommentJdbcRepository}），替代 {@code OFFSET}，复杂度恒为 O(LIMIT) 与翻页深度无关；
 *       游标由 {@link #encode(Comment)} 编码为不透明 token 下发前端。</li>
 *   <li><b>总数 = Redis INCR</b>：{@link CommentCountService} 在发布时原子 +1，读路径 O(1) 取，
 *       避免 {@code COUNT(*)} 扫膨胀表（支撑「快速增长的评论」实时计数）。</li>
 *   <li><b>热头优先</b>：最新首页走 {@link CommentHotService#recentHead}（ZSET，O(log N)），
 *       不扫大表；更深页由 keyset 桥接 DB。最热 tab 走 {@code tf:comment:hot} ZSET。</li>
 *   <li><b>点赞 = Redis 64 槽</b>：{@link CommentLikeService} 抖音式 toggle 去重 + 计数，
 *       DB {@code like_count} 仅灾备冗余；列表读经 {@link CommentLikeService#likeCounts} 一次
 *       pipeline 聚合后回填 {@code likeCount}。</li>
 *   <li><b>落库异步化 / 削峰</b>：主 INSERT 经 {@link CommentEventPublisher} 解耦——默认走进程内
 *       {@code interactionExecutor} 异步刷盘（零回归），启用 MQ（{@code --spring.profiles.active=mq}）
 *       后改发 RocketMQ 顺序事件、由 {@code CommentPersistConsumer} 幂等落库，把瞬时写峰值削平。
 *       Redis 为权威，落库仅灾备；fail-open：落库异常只告警，不影响主流程。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    /** 评论发布用户维度限流 key 前缀（与上传 rl:upload: / 点赞 rl:like: 隔离计数，共用阈值配置）。 */
    private static final String RL_COMMENT_PREFIX = "rl:comment:";
    /** 评论点赞用户维度限流 key 前缀（与发布隔离计数）。 */
    private static final String RL_COMMENT_LIKE_PREFIX = "rl:comment:like:";

    private final CommentJdbcRepository commentRepository;
    private final ContentSecurityService contentSecurityService;
    /** 雪花 ID 生成器由 {@code SnowflakeConfig} 装配（workerId/datacenterId 必须逐实例区分）。 */
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    /** 评论区异常感知（P1 #133）：速率/聚集异常检测，fail-open。 */
    private final CommentRiskService commentRiskService;
    /** 用户维度限流（跨实例统一计数，复用上传同款 Redis 固定窗口）。 */
    private final UploadRateLimiter rateLimiter;
    /** 复审任务仓储（进 COMMENT_ANOMALY 巡查队列，复用 review_task 单表）。 */
    private final ReviewTaskRepository reviewTaskRepository;
    /** 评论区风控配置（turbofeed.media.comment-risk.*）。 */
    private final MediaProperties properties;
    /** 异常行为日志（P2-2）：评论异常行为持久化，供 P2-1 信号闭环来源（fail-open）。 */
    private final BehaviorLogService behaviorLogService;
    /** 评论总数计数（Redis INCR，冷启动回退 DB）。 */
    private final CommentCountService commentCountService;
    /** 评论热头（最新 / 最热 ZSET）。 */
    private final CommentHotService commentHotService;
    /** 评论点赞（Redis 64 槽去重 + 计数 + 异步落库 + 接 tf-counter）。 */
    private final CommentLikeService commentLikeService;

    /** 评论落库发布器（发布/订阅解耦，opt-in 削峰）：默认进程内异步落库，启用 MQ 后改经 RocketMQ 削峰。 */
    private final CommentEventPublisher commentEventPublisher;

    /**
     * 发布评论（顶层或回复）。
     *
     * @param mediaId  被评论内容 ID（分片键，必传）
     * @param userId   评论者（来自 JWT）
     * @param content  评论文本（上限见 {@code turbofeed.content-security.scene-max-length.COMMENT}）
     * @param parentId 父评论 ID；0 = 顶层评论
     * @return 落库后的评论（含生成的 commentId）
     * @throws BizException PARAM_ERROR / SENSITIVE_WORD_HIT / NOT_FOUND
     */
    public Comment publish(String mediaId, long userId, String content, long parentId) {
        if (mediaId == null || mediaId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mediaId 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评论内容不能为空");
        }
        // ⑤ 用户维度限流（跨实例统一计数，复用上传同款 Redis 固定窗口；前缀隔离计数，不与点赞互扰）
        if (!rateLimiter.tryAcquire(String.valueOf(userId), RL_COMMENT_PREFIX)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "评论过于频繁，请稍后再试");
        }
        // 长度上限 + 敏感词（含归一化抗绕过与白名单豁免）
        contentSecurityService.requireClean(ContentScene.COMMENT, content, userId);

        // #133 评论区异常感知（速率/聚集）：先过风险检测再落库
        CommentRiskService.Assessment risk = commentRiskService.assess(mediaId, userId);
        if (risk.level() == CommentRiskService.RiskLevel.USER_THROTTLED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评论过于频繁，请稍后再试");
        }

        long rootId;
        if (parentId == 0) {
            rootId = 0; // 顶层
        } else {
            CommentJdbcRepository.ParentLocation parent = commentRepository.findParent(mediaId, parentId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "父评论不存在"));
            // 父为顶层 → 新回复的根 = 父的 commentId；父已是回复 → 沿用父的 rootId
            rootId = parent.rootId() == 0 ? parent.commentId() : parent.rootId();
        }

        long commentId = snowflakeIdGenerator.nextId();
        // #133：速率异常且开启折叠 → 该评论 FOLDED（前端默认不展示），否则 APPROVED
        boolean spikeFold = risk.level() == CommentRiskService.RiskLevel.CONTENT_SPIKE
                && properties.getCommentRisk().isFoldOnSpike();
        CommentStatus status = spikeFold ? CommentStatus.FOLDED : CommentStatus.APPROVED;
        Comment c = new Comment(
                commentId, mediaId, userId, rootId, parentId, content,
                status, 0, Instant.now());
        // ③ 评论落库异步化：经 CommentEventPublisher 解耦（默认进程内 interactionExecutor 异步落库，
        // 启用 MQ 后改发 RocketMQ 顺序事件、由 CommentPersistConsumer 幂等落库），主链路不阻塞 DB 写；
        // Redis 计数为权威，落库仅为灾备重建用；fail-open：落库异常只告警，不影响评论发布主流程。
        commentEventPublisher.publish(c);
        // 总数 +1（Redis INCR，支撑快速增长的评论实时计数）
        commentCountService.incr(mediaId);
        // 热头写入 ZSET（最新头 + 最热头初始 0），读路径热头优先不扫大表
        commentHotService.add(c);
        // #133：评论区速率异常 → 进 REVIEWER 巡查队列（仅巡查，不下架内容），防重复建单
        if (spikeFold && !reviewTaskRepository.existsOpenForMedia(mediaId)) {
            reviewTaskRepository.insert(mediaId, userId, ReviewTask.TYPE_COMMENT_ANOMALY,
                    (int) Math.min(risk.rateCount(), Integer.MAX_VALUE));
            log.info("评论区速率异常→建巡查任务(COMMENT_ANOMALY，REVIEWER 二次研判): mediaId={}, rateCount={}", mediaId, risk.rateCount());
            // 异常行为日志（P2-2）：评论异常行为归评论者，供 P2-1 信号闭环来源（fail-open，不影响主流程）
            behaviorLogService.record(userId, mediaId, "COMMENT_ANOMALY", "rateCount=" + risk.rateCount());
        }
        return c;
    }

    /**
     * 顶层评论列表（keyset 游标 + 总数 + 热头优先）。
     *
     * @param mediaId 内容 ID
     * @param size    本页大小（默认 20，上限 100）
     * @param cursor  上一页 {@link CommentPage#nextCursor()}；空=首页
     * @param mode    {@code newest}（默认，最新优先）/ {@code hot}（最热优先）
     * @return 含游标与总数的分页视图
     */
    public CommentPage listTopLevel(String mediaId, int size, String cursor, String mode) {
        int limit = size <= 0 ? 20 : Math.min(size, 100);
        List<Comment> items;
        if ("hot".equalsIgnoreCase(mode)) {
            // 最热 tab：tf:comment:hot ZSET 取最热 N 条（score=likeCount）
            items = fetchOrdered(mediaId, commentHotService.hotHead(mediaId, limit));
        } else {
            if (cursor == null || cursor.isBlank()) {
                // 最新首页：热头 ZSET 优先（O(log N) 不扫大表），冷/空回退 DB 有序索引
                List<Long> ids = commentHotService.recentHead(mediaId, limit);
                items = fetchOrdered(mediaId, ids);
                if (items.isEmpty()) {
                    items = commentRepository.listTopLevelFresh(mediaId, limit);
                }
            } else {
                // 更深页：keyset 游标桥接 DB，(created_at, comment_id) 严格早于游标，倒序
                Cursor cur = decode(cursor);
                items = commentRepository.listTopLevelKeyset(mediaId, limit, cur.createdAt(), cur.commentId());
            }
        }
        enrichLikeCounts(items);
        long total = commentCountService.getCount(mediaId);
        boolean hasMore = items.size() == limit;
        String next = hasMore && !items.isEmpty() ? encode(items.get(items.size() - 1)) : "";
        return new CommentPage(items, next, total, hasMore);
    }

    /**
     * 某根评论下的回复（keyset 游标）。回复无独立总数（前端按「共 N 条回复」可选另算），total 置 0。
     */
    public CommentPage listReplies(String mediaId, long rootId, int size, String cursor) {
        if (rootId <= 0) {
            return new CommentPage(List.of(), "", 0, false);
        }
        int limit = size <= 0 ? 50 : Math.min(size, 200);
        List<Comment> items;
        if (cursor == null || cursor.isBlank()) {
            items = commentRepository.listRepliesFresh(mediaId, rootId, limit);
        } else {
            Cursor cur = decode(cursor);
            items = commentRepository.listRepliesKeyset(mediaId, rootId, limit, cur.createdAt(), cur.commentId());
        }
        enrichLikeCounts(items);
        boolean hasMore = items.size() == limit;
        String next = hasMore && !items.isEmpty() ? encode(items.get(items.size() - 1)) : "";
        return new CommentPage(items, next, 0, hasMore);
    }

    /**
     * 点赞 / 取消赞（抖音式 toggle）。
     *
     * @return 操作后的「是否已赞」态（前端据此切换图标 + 本地调整计数）
     */
    public boolean like(String mediaId, long commentId, long userId) {
        // 用户维度限流（与发布前缀隔离，共用阈值配置）
        if (!rateLimiter.tryAcquire(String.valueOf(userId), RL_COMMENT_LIKE_PREFIX)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "点赞过于频繁，请稍后再试");
        }
        boolean nowLiked = commentLikeService.toggle(mediaId, commentId, userId);
        // 最热头分数随点赞状态变化实时调整
        commentHotService.onLike(commentId, mediaId, nowLiked ? 1 : -1);
        return nowLiked;
    }

    /** 按热头顺序（ZSET 返回顺序）用 {@code WHERE media_id=? AND comment_id IN(...)} 单分片取正文并重排。 */
    private List<Comment> fetchOrdered(String mediaId, List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        List<Comment> rows = commentRepository.listByIds(mediaId, orderedIds);
        Map<Long, Comment> byId = new java.util.LinkedHashMap<>();
        for (Comment c : rows) {
            byId.put(c.commentId(), c);
        }
        List<Comment> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Comment c = byId.get(id);
            if (c != null) {
                ordered.add(c);
            }
        }
        return ordered;
    }

    /** 列表读后批量回填 Redis 点赞数（一次 pipeline 聚合 64 槽）。 */
    private void enrichLikeCounts(List<Comment> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> ids = items.stream().map(Comment::commentId).toList();
        Map<Long, Long> counts = commentLikeService.likeCounts(ids);
        for (int i = 0; i < items.size(); i++) {
            Comment o = items.get(i);
            long c = counts.getOrDefault(o.commentId(), (long) o.likeCount());
            if (c != o.likeCount()) {
                items.set(i, new Comment(o.commentId(), o.mediaId(), o.userId(), o.rootId(), o.parentId(),
                        o.content(), o.status(), (int) Math.min(c, Integer.MAX_VALUE), o.createdAt()));
            }
        }
    }

    /** keyset 游标：编码 {@code (createdAt, commentId)} 为不透明 base64 token。 */
    private static String encode(Comment c) {
        String raw = c.createdAt().toEpochMilli() + ":" + c.commentId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码 keyset 游标 token → (createdAt, commentId)。 */
    private static Cursor decode(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int idx = raw.indexOf(':');
        long epoch = Long.parseLong(raw.substring(0, idx));
        long cid = Long.parseLong(raw.substring(idx + 1));
        return new Cursor(Instant.ofEpochMilli(epoch), cid);
    }

    /** 解码后的游标（createdAt + commentId）。 */
    private record Cursor(Instant createdAt, long commentId) {
    }
}
