package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.MediaTagJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.repository.AppealRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.event.outbox.TimelineAppendPayload;
import com.turbofeed.gateway.service.event.outbox.TimelineRemovePayload;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.shared.caption.CaptionTagParser;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 媒体审核服务：维护内容审核状态机，并执行「机审初筛 + 账号信用分级（先发/先审）+ 多池发布 + 举报/申诉闭环」。
 *
 * <p><b>抖音式分层</b>：上传后先跑机审初筛（{@link ContentModerationRouter}，MVP=本地规则引擎 RULE）；
 * 机审通过的内容按<b>账号信用等级</b>分流——L0 低信用/新号<b>先审后放</b>（进 PENDING 等人审），
 * L1/L2 高信用<b>先发后审</b>（直接 APPROVED 进对应流量池，靠举报/人审兜底）；机审 REJECTED 立即拦截。
 * 已发布内容可被用户举报、作者申诉，形成完整治理闭环。</p>
 *
 * <h3>整帖一审（一帖多图）</h3>
 * <p>一次上传批次的 N 张图构成一个<b>帖子</b>（{@code post_id}），审核以<b>帖</b>为单位：
 * 机审一次、状态一次翻转、公域时间线一次投递。<b>整帖同上同下</b>——任一图被驳回即整帖驳回，
 * 下架/申诉也作用在整帖上。这不是实现便利，而是内容安全的必要条件：若允许「一帖内一半可见」，
 * 同一条帖子在不同入口会呈现互相矛盾的可见性。</p>
 *
 * <p><b>操作对象统一为「帖代表媒体 ID」</b>：{@code media_id} 是 media 表主键、{@code post_id} 不是，
 * 因此外部接口（含管理端）仍以 {@code mediaId} 为参数，本服务负责把它解析到帖子并落到整帖；
 * 这是「接口签名不变、语义升级为整帖」的关键，避免前端与管理端全部改签名。</p>
 *
 * <p><b>存储边界</b>：审核状态已落库（{@link MediaJdbcRepository}，media 表 status 列），重启不丢失；
 * 公域可见性由 tf-feed-engine 的时间线读模型物化（多池 ZSET），发布即按信用进对应池。
 * 本服务通过 {@link FeedTimelinePublisher} 投递入流/下架（mq 关闭时走同步 HTTP 兜底、开启时走
 * RocketMQ 顺序消息且 fail-fast，由 MQ 重试/DLQ 兜底）：可见性是审核的下一跳，仍不能让引擎抖动
 * 反向阻断审核状态机本身（mq 模式下靠 @Transactional 回滚保证不丢）。</p>
 */
@Service
public class MediaReviewService {

    /**
     * 显式声明日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}）：
     * 本机构建环境对被修改文件的 Lombok 注解处理不生效，会报「log 找不到符号 / final 字段未初始化」。
     */
    private static final Logger log = LoggerFactory.getLogger(MediaReviewService.class);

    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    private final MediaJdbcRepository mediaRepository;
    private final MediaTagJdbcRepository mediaTagRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModerationRouter contentModeration;
    private final MediaProperties properties;
    private final AccountCreditService accountCreditService;
    private final ReportRepository reportRepository;
    private final AppealRepository appealRepository;
    /** 复审任务队列（抖音式「举报累计→人工复审」，changelog 0065）。 */
    private final ReviewTaskRepository reviewTaskRepository;
    /** 发件箱（P0-3）：时间线投递改走「同事务落事件 + 提交后直投 + 中继补偿」。 */
    private final OutboxService outboxService;
    /** 处罚域（penalty 集成缝接线）：违规确认落处罚、申诉翻案解除封禁。 */
    private final PenaltyService penaltyService;

    public MediaReviewService(MediaJdbcRepository mediaRepository,
                              MediaTagJdbcRepository mediaTagRepository,
                              StringRedisTemplate redisTemplate,
                              ContentModerationRouter contentModeration,
                              MediaProperties properties,
                              AccountCreditService accountCreditService,
            ReportRepository reportRepository,
            AppealRepository appealRepository,
            ReviewTaskRepository reviewTaskRepository,
            OutboxService outboxService,
                              PenaltyService penaltyService) {
        this.mediaRepository = mediaRepository;
        this.mediaTagRepository = mediaTagRepository;
        this.redisTemplate = redisTemplate;
        this.contentModeration = contentModeration;
        this.properties = properties;
        this.accountCreditService = accountCreditService;
        this.reportRepository = reportRepository;
        this.appealRepository = appealRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.outboxService = outboxService;
        this.penaltyService = penaltyService;
    }

    /**
     * 上传事件处理入口（本地 Spring 事件与 RocketMQ 消费者共用，审核逻辑单一来源）。
     *
     * <p>流程：整帖落库 PENDING → 机审初筛（整帖一次）→ 按账号信用分级决定先发后审 / 先审后放。</p>
     *
     * <p><b>为什么这里要兜底落库整帖</b>：正常路径由上传服务先同步落库（消灭「存储成功但事件丢失」
     * 的孤儿对象），本方法再以 {@code media_id} 主键幂等重写一遍。事件重投（MQ 重试 / DLQ 后人工重放）
     * 时，若上传服务那一步曾被回滚，这里的兜底插入能把整帖补齐，避免「状态已流转但行不存在」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        String representativeId = event.representativeMediaId();
        if (representativeId == null) {
            // 空帖事件（不该出现）：不抛异常，避免一条坏消息把 MQ 拖进无意义的重试/DLQ 循环
            log.warn("收到不含任何媒体的上传事件，忽略: postId={}, userId={}", event.postId(), userId);
            return;
        }

        MediaStatus existing = mediaRepository.getStatus(representativeId, userId);
        if (existing != null && existing != MediaStatus.PENDING) {
            // 重复投递：已是终态，幂等返回；APPROVED 兜底补齐时间线（按信用池）
            if (existing == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                publishAppend(representativeId, userId, toTimelinePost(event), level.poolLevel());
            }
            return;
        }

        // 兜底落库整帖（命中主键即幂等更新；seq 取下标，与上传服务写入的顺序一致）
        List<String> mediaIds = event.mediaIds();
        List<String> urls = event.urls();
        for (int i = 0; i < mediaIds.size(); i++) {
            mediaRepository.insert(event.postId(), mediaIds.get(i), userId, urls.get(i),
                    MediaStatus.PENDING, event.caption(), event.captionMark(), i, event.occurredAt(), null);
        }

        // —— 机审初筛（整帖一次；以首图 url 作为判定输入）——
        MediaStatus machine = contentModeration.moderate(representativeId, userId, urls.get(0));

        if (properties.getReview().isAutoPass()) {
            // 演示占位：机审结果直接放行（仅供本地联调）
            ReviewOutcome outcome = review(representativeId, userId, machine == MediaStatus.APPROVED);
            if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                publishAppend(representativeId, userId, toTimelinePost(event), level.poolLevel());
            }
            return;
        }

        if (machine == MediaStatus.REJECTED) {
            // 机审驳回即拦：整帖翻 REJECTED，不进人工队列
            review(representativeId, userId, false);
            log.info("机审驳回即拦：整帖判定 REJECTED（不进人审队列）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }

        // 机审通过/降级：按账号信用分级分流
        CreditLevel level = accountCreditService.ensure(userId);
        if (level == CreditLevel.L0) {
            // 先审后放：机审通过仍进 PENDING，等人审终裁（不自动进公域）
            log.info("低信用账户：机审通过转人审队列（先审后放，整帖）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }
        // 先发后审（L1/L2）：整帖直接 APPROVED 进对应流量池（小池/大池），靠举报/人审兜底
        ReviewOutcome outcome = review(representativeId, userId, true);
        if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
            publishAppend(representativeId, userId, toTimelinePost(event), level.poolLevel());
        }
    }

    /**
     * 审核动作的结果。
     *
     * <p>{@code status} 是本次调用<b>结束后</b>帖子所处的状态；{@code transitioned} 表示
     * <b>本次调用</b>是否真正引起了状态流转。</p>
     *
     * <p><b>为什么必须区分「结果状态」与「是否由我流转」</b>：调用方在状态变为 APPROVED 后要投递
     * 公域时间线。若只看 {@code status == APPROVED}，那么「CAS 落空、帖子刚被另一条路径审过」
     * 也会被误判成「我审的」，同一条帖子被投递两次（Redis ZSET 幂等，但反查索引/推荐旁路缓存
     * 会多一次写放大）。{@code transitioned = false} 时调用方必须放弃投递——流转方自己会投。</p>
     */
    public record ReviewOutcome(MediaStatus status, boolean transitioned) {
    }

    /**
     * 执行审核动作：PENDING -> APPROVED / REJECTED（终态），<b>作用于整帖</b>。
     *
     * <p>{@code mediaId} 用于定位帖子；仓库层的 CAS 会把状态落到帖内全部行。</p>
     *
     * <p><b>为什么是 CAS 而不是「读后直写」</b>：本方法原先「先 {@code getStatus} 读、再
     * {@code updateStatus} 写」，两步之间没有互斥，是典型的检查-后-执行（TOCTOU）。同一帖存在
     * 两条并发审核路径——上传后的异步「先发后审」（{@link AccountCreditService} 对无信用记录的
     * 账号默认 L1，故 {@code handleUploaded} 会直接置 APPROVED）与管理员人工审核
     * {@link #reviewByMediaId}。两者若都在对方提交前读到 PENDING，就会各自发起一次<b>整帖多行</b>
     * UPDATE；帖内多行更新需要在二级索引 {@code idx_user_post} 与聚簇主键间往返加锁，加锁顺序相反
     * 时即形成 InnoDB 死锁（取所见 2026-09-14 {@code SHOW ENGINE INNODB STATUS}）。
     * 带上 {@code status = 期望前置态} 后，并发双写收敛为「一次生效 + 一次幂等返回」，
     * 且加锁范围收窄到「真正需要改的行」。</p>
     *
     * <p><b>CAS 落空不是错误</b>：影响行数为 0 说明帖子已被另一条路径流转（或本来就是终态），
     * 本次调用不改任何数据、不重复投递时间线，回读真实状态后原样返回，对调用方是幂等成功。</p>
     */
    public ReviewOutcome review(String mediaId, long userId, boolean approved) {
        MediaStatus current = mediaRepository.getStatus(mediaId, userId);
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未找到待审核记录: mediaId=" + mediaId);
        }
        if (current != MediaStatus.PENDING) {
            log.info("审核终态幂等返回（不重复流转）: mediaId={}, status={}", mediaId, current);
            return new ReviewOutcome(current, false);
        }
        MediaStatus target = approved ? MediaStatus.APPROVED : MediaStatus.REJECTED;
        int rows = mediaRepository.updateStatusCas(mediaId, userId, current, target);
        if (rows == 0) {
            // 读状态与 CAS 之间，帖子被另一条路径流转了：本次什么都没改，
            // 时间线由真正流转的那一方投递，这里绝不重复投递。
            MediaStatus actual = mediaRepository.getStatus(mediaId, userId);
            log.info("审核 CAS 落空（已被其他路径流转，本次幂等返回）: mediaId={}, expected={}, actual={}",
                    mediaId, current, actual);
            return new ReviewOutcome(actual == null ? current : actual, false);
        }
        evictCache(mediaId, userId);
        log.info("审核状态流转（整帖）: mediaId={}, {} -> {}, rows={}", mediaId, current, target, rows);
        return new ReviewOutcome(target, true);
    }

    /**
     * 管理员审核入口：PENDING → APPROVED / REJECTED，通过即按信用把<b>整帖</b>放进对应流量池。
     *
     * <p>投递时间线的条件是「<b>本次调用真的完成了流转</b>」，而不是「结果状态是 APPROVED」：
     * 若管理员的点击与异步先发后审撞在一起，CAS 只有一方命中，落空的一方不得再投递一次。</p>
     *
     * <p><b>事务与投递顺序（P0-4 + P0-3）</b>：整段包 {@code @Transactional}，使「状态 CAS 翻转 +
     * 新人观察期计数 + <b>发件箱事件行</b>」原子提交。投递分两步：事件行先随事务落库
     * （事务提交则消息必然存在，不会丢；事务回滚则消息一并消失，不会发出幽灵消息），
     * 提交后由 {@link OutboxService#deliverAfterCommit} 直投一次保证低延迟，
     * 失败则由 {@code OutboxRelay} 轮询重投（至少一次 + 消费端幂等）。
     * 这补上了改造前「提交后投递、失败只记日志」留下的 fail-open 缺口。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public MediaStatus reviewByMediaId(String mediaId, boolean approved) {
        long userId = parseUserId(mediaId);
        ReviewOutcome outcome = review(mediaId, userId, approved);
        if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
            MediaItem post = asTimelinePost(mediaRepository.findMedia(mediaId, userId), userId);
            if (post != null) {
                CreditLevel level = accountCreditService.ensure(userId);
                // P0-3（changelog 0037）：时间线投递改走发件箱。
                //   ① 事件行与「状态翻转 + 观察期计数」写在<b>同一个本地事务</b>里：
                //      事务回滚 → 事件一并消失（不会发出「DB 里不存在的帖子」的幽灵消息）；
                //      事务提交 → 事件必然存在（不会因投递失败而永久丢失）。
                //   ② 提交后直投一次（低延迟），失败不抛——行留在库里，由 OutboxRelay 补偿重投。
                // 改造前「提交后投递、失败只记日志」= fail-open：内容已可见却没进时间线，且无人重试。
                long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_APPEND, mediaId, userId,
                        new TimelineAppendPayload(post, level.poolLevel()));
                outboxService.deliverAfterCommit(eventId);
            }
            // 人工通过计数：新人观察期据此解除。只统计人工路径——先发后审的自动通过不计入，
            // 否则新号第一帖上传即把自己顶出观察期，观察期形同虚设。
            accountCreditService.onHumanApproved(userId, properties.getReview().getNewUserApproveThreshold());
        }
        return outcome.status();
    }

    /**
     * 用户举报：仅已发布内容可被举报。高危类目（涉政/暴恐/儿童）立即下架停推（fail-closed）；
     * 普通举报写表进人工队列，由 {@link #handleReport} 复核。
     *
     * <p>下架作用于<b>整帖</b>（帖内任一图被举报即整帖不可见），避免「举报了 9 张里的 1 张，
     * 其余 8 张继续可见」的绕过路径。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void report(String mediaId, long reporterUserId, String reason) {
        long authorId = parseUserId(mediaId);
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur == null || cur != MediaStatus.APPROVED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅已发布内容可被举报");
        }
        reportRepository.insert(mediaId, reporterUserId, reason);
        if (isHighRisk(reason)) {
            // CAS 保证「真正完成下架的那一次」才扣信用+落处罚；其余并发举报得 0 行幂等返回。
            int rows = takeDownAndPenalize(mediaId, authorId, ViolationSource.HUMAN_REPORT, "SYSTEM",
                    "高危举报立即下架停推: " + mediaId, ViolationSeverity.HIGH);
            if (rows == 0) {
                log.info("高危举报下架 CAS 落空（已被其他路径下架，不重复扣信用）: mediaId={}", mediaId);
            } else {
                log.warn("高危举报立即下架停推（整帖）: mediaId={}, reporterUserId={}, reason={}",
                        mediaId, reporterUserId, reason);
            }
            return;
        }
        // 普通举报：累计达阈值 → 自动建复审任务（抖音式「举报累计→人工复核」），归 REVIEWER 二次研判。
        maybeEscalateToReviewTask(mediaId, authorId);
    }

    /** 管理员处理举报：确认违规→整帖 TAKEN_DOWN + 扣信用；驳回→内容保持。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleReport(String mediaId, boolean confirmed) {
        long authorId = parseUserId(mediaId);
        if (confirmed) {
            // CAS 保证单次：把帖子翻下去的那一次才扣信用+落处罚；已被其它路径下架则得 0 行幂等。
            int rows = takeDownAndPenalize(mediaId, authorId, ViolationSource.HUMAN_REPORT, "ADMIN",
                    "举报确认违规→整帖下架: " + mediaId, ViolationSeverity.MID);
            if (rows == 0) {
                log.info("举报确认违规：内容已不在已发布态，不重复下架/扣信用: mediaId={}", mediaId);
            }
        }
        reportRepository.resolve(mediaId, confirmed);
    }

    /**
     * REVIEWER 二次研判复审任务（抖音式「举报累计→人工复核」的承接动作）。
     *
     * <p>违规→整帖 TAKEN_DOWN + 扣信用 + 落处罚（与高危举报同处置力度）；无违规→维持发布。
     * 无论哪种，都一并清理该 media 的全部待处理举报与待复审任务，避免重复处置。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void decideReviewTask(long taskId, boolean takedown, String resolver) {
        ReviewTask task = reviewTaskRepository.findById(taskId);
        if (task == null || task.status() != ReviewTask.STATUS_PENDING) {
            throw new BizException(ErrorCode.PARAM_ERROR, "复审任务不存在或已处置");
        }
        String mediaId = task.mediaId();
        long authorId = task.authorId();
        if (takedown) {
            // CAS 保证「真正完成翻转的那一次」才扣信用+落处罚；已被其它路径下架则幂等不双计。
            takeDownAndPenalize(mediaId, authorId, ViolationSource.HUMAN_REPORT, resolver,
                    "复审任务确认违规(举报累计): " + mediaId, ViolationSeverity.MID);
            reviewTaskRepository.resolveAllForMedia(mediaId, ReviewTask.STATUS_TAKEDOWN, resolver);
            reportRepository.resolve(mediaId, true);
            log.info("复审任务确认违规→整帖下架: taskId={}, mediaId={}, resolver={}", taskId, mediaId, resolver);
        } else {
            // 维持发布：任务置 RESOLVED，待处理举报标记为不成立。
            reviewTaskRepository.resolveAllForMedia(mediaId, ReviewTask.STATUS_RESOLVED, resolver);
            reportRepository.resolve(mediaId, false);
            log.info("复审任务判定无违规，维持发布: taskId={}, mediaId={}, resolver={}", taskId, mediaId, resolver);
        }
    }

    /** REVIEWER 复审队列：按状态列出任务（默认 0=待复审）。 */
    public List<ReviewTask> listReviewTasks(int status) {
        return reviewTaskRepository.findByStatus(status);
    }

    /**
     * 举报累计达阈值 → 自动建一条复审任务（抖音式「举报累计→人工复核」）。
     *
     * <p>仅在该内容仍处于已发布态、且无未决复审任务时建单；REVIEWER 在
     * {@link #decideReviewTask} 二次研判（违规→下架+处罚 / 无违规→维持发布）。</p>
     */
    private void maybeEscalateToReviewTask(String mediaId, long authorId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur != MediaStatus.APPROVED) {
            return; // 已不在公域（下架/申诉中），不再建复审任务
        }
        int pending = reportRepository.countPending(mediaId);
        if (pending < properties.getReview().getReportReReviewThreshold()) {
            return;
        }
        if (reviewTaskRepository.existsOpenForMedia(mediaId)) {
            return; // 已有待复审任务，避免重复建单
        }
        reviewTaskRepository.insert(mediaId, authorId, ReviewTask.TYPE_REPORT_ACCUMULATED, pending);
        log.info("举报累计达阈值，自动建复审任务（REVIEWER 二次研判）: mediaId={}, pendingReports={}, threshold={}",
                mediaId, pending, properties.getReview().getReportReReviewThreshold());
    }

    /**
     * 把整帖从 APPROVED 翻 TAKEN_DOWN + 移出公域 + 扣信用 + 落处罚（高危举报 / 管理员确认 / 复审任务共用）。
     *
     * <p>CAS 保证「真正完成翻转的那一次」才扣信用/落处罚（并发或重复处置幂等，不双计）。
     * 返回受影响行数（0 表示内容已被其它路径下架，本次不重复处置）。</p>
     */
    private int takeDownAndPenalize(String mediaId, long authorId, ViolationSource source,
                                    String operator, String reason, ViolationSeverity severity) {
        int rows = mediaRepository.updateStatusCas(mediaId, authorId, MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN);
        if (rows > 0) {
            removeFromTimeline(mediaId, authorId);
            accountCreditService.onViolationConfirmed(authorId);
            // penalty 集成缝：确认违规 → 落处罚（与信用扣分同触发点，CAS 保证单次）。
            penaltyService.recordViolation(authorId, ViolationCategory.OTHER, severity,
                    source, null, operator, reason);
        }
        return rows;
    }

    /**
     * 作者申诉：仅被驳回/下架内容可申。整帖翻 APPEALING（暂不可见），写表进人工复核。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(String mediaId, long authorUserId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorUserId);
        if (cur == null || (cur != MediaStatus.REJECTED && cur != MediaStatus.TAKEN_DOWN)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅被驳回/下架内容可申诉");
        }
        int rows = mediaRepository.updateStatusCas(
                mediaId, authorUserId, cur, MediaStatus.APPEALING);
        if (rows == 0) {
            // 并发重复申诉：另一条请求已把帖子翻成 APPEALING（并写了自己的申诉单），本次幂等返回。
            log.info("申诉 CAS 落空（已被其他路径流转，不重复写申诉单）: mediaId={}, expected={}",
                    mediaId, cur);
            return;
        }
        removeFromTimeline(mediaId, authorUserId); // 申诉中暂不可见
        appealRepository.insert(mediaId, authorUserId);
        log.info("作者申诉（整帖暂不可见）: mediaId={}, authorUserId={}", mediaId, authorUserId);
    }

    /** 管理员处理申诉：翻案→整帖 APPROVED 恢复公域（按信用池）+ 信用加回；维持→整帖 TAKEN_DOWN。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(String mediaId, boolean upheld) {
        long authorId = parseUserId(mediaId);
        if (upheld) {
            // CAS 前置态为 APPEALING：只有真正完成「申诉中 → 已发布」的那一次才恢复公域与加信用，
            // 避免管理员重复点击导致同帖被投递多次、信用被重复加回。
            int rows = mediaRepository.updateStatusCas(
                    mediaId, authorId, MediaStatus.APPEALING, MediaStatus.APPROVED);
            if (rows > 0) {
                MediaItem post = asTimelinePost(mediaRepository.findMedia(mediaId, authorId), authorId);
                if (post != null) {
                    CreditLevel level = accountCreditService.ensure(authorId);
                    // 与 reviewByMediaId 同一套路：同事务落事件 + 提交后直投（失败由中继补偿）。
                    publishAppend(mediaId, authorId, post, level.poolLevel());
                }
                accountCreditService.onAppealUpheld(authorId);
                // penalty 集成缝：申诉翻案 → 解除原处罚（误封永久封可由此撤销；非封禁态 liftPenalty 幂等 no-op）。
                penaltyService.liftPenalty(authorId, ViolationSource.APPEAL_OVERTURN, "ADMIN",
                        "申诉翻案→解除封禁恢复: " + mediaId);
                log.info("申诉翻案→整帖恢复公域: mediaId={}", mediaId);
            } else {
                log.info("申诉翻案 CAS 落空（内容不在申诉中态，不重复恢复/加信用）: mediaId={}", mediaId);
            }
        } else {
            int rows = mediaRepository.updateStatusCas(
                    mediaId, authorId, MediaStatus.APPEALING, MediaStatus.TAKEN_DOWN);
            log.info("申诉维持原状: mediaId={}, rows={}", mediaId, rows);
        }
        appealRepository.resolve(mediaId, upheld);
    }

    /**
     * 事件 → 公域时间线条目（物化进 Redis ZSET 的 {@code FeedItemView} JSON，含描述/标题与整帖图片）。
     *
     * <p>描述与图片列表随事件透传而非回查 DB：审核发布是热路径，避免为拿 caption 与 images
     * 多两轮分片查询；事件与首落库携带同一份数据，物化结果与库中一致，Feed 直接可展示。</p>
     */
    private static MediaItem toTimelinePost(MediaUploadedEvent event) {
        String representativeId = event.representativeMediaId();
        String cover = event.urls().isEmpty() ? null : event.urls().get(0);
        return new MediaItem(event.postId(), representativeId, cover, event.urls(), 0,
                MediaStatus.APPROVED, event.occurredAt(), event.caption(), event.captionMark(),
                CaptionTagParser.parse(event.caption()));
    }

    /**
     * 把<b>行级</b>条目升级为可入流的<b>整帖</b>条目：补齐帖内全部图片（按 seq 升序）。
     *
     * <p>{@code findMedia} 返回的行级视图 {@code images} 只含自身，直接入流会让一帖九图在
     * 公域只显示一张。历史单图帖（{@code post_id} 为空）天然只有一张，走单元素列表。</p>
     */
    private MediaItem asTimelinePost(MediaItem row, long userId) {
        if (row == null) {
            return null;
        }
        List<String> images;
        if (row.postId() == null || row.postId().isBlank()) {
            images = row.url() == null ? List.of() : List.of(row.url());
        } else {
            List<MediaItem> rows = mediaRepository.listPostImages(userId, row.postId());
            images = new ArrayList<>(rows.size());
            for (MediaItem r : rows) {
                if (r.url() != null) {
                    images.add(r.url());
                }
            }
            if (images.isEmpty()) {
                images = row.url() == null ? List.of() : List.of(row.url());
            }
        }
        return new MediaItem(row.postId(), row.mediaId(), row.url(), images, 0,
                MediaStatus.APPROVED, row.createdAt(), row.caption(), row.captionMark(),
                CaptionTagParser.parse(row.caption()));
    }

    /**
     * 把帖从公域时间线摘除：<b>必须用与投递时相同的键</b>（有 postId 用 postId，
     * 历史单图数据回退 mediaId），否则新旧数据会落在两个反查索引上，下架失效。
     */
    private void removeFromTimeline(String mediaId, long userId) {
        String postId = mediaRepository.findPostId(mediaId, userId);
        String key = postId == null || postId.isBlank() ? mediaId : postId;
        // 改走发件箱：下架是「安全动作」，丢了比晚了更糟——内容已在 DB 下架，
        // 若 remove 投递丢失，公域会一直展示已下架内容（改造前正是这个 fail-open）。
        // 顺序安全：事件按雪花 id 有序消费，且 append 在过审时就已直投，
        // remove 一定晚于它，不存在「remove 先到、append 后到」把内容又放回来的情况。
        publishRemove(mediaId, userId, key);
        // 内容标签冷存同步摘除：key 与投递时同口径（见 publishAppend），否则新旧标签
        // 会落在两个索引上。best-effort：清理失败不影响 content 已在 DB 下架这一事实。
        mediaTagRepository.remove(key);
    }

    /** 入流：同事务落发件箱事件 + 提交后直投（失败留行，由 OutboxRelay 补偿重投）。 */
    private void publishAppend(String mediaId, long userId, MediaItem post, int poolLevel) {
        long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_APPEND, mediaId, userId,
                new TimelineAppendPayload(post, poolLevel));
        outboxService.deliverAfterCommit(eventId);
        // 内容标签冷存同步写入：键用 post.timelineKey()（= Redis tf:media:tags 同源），
        // 标签由 caption 解析、随 post 透传，避免二次解析分叉。best-effort：冷存写入失败
        // 不阻断发布链路（Redis 才是读源，本表可重建）。
        mediaTagRepository.save(post.timelineKey(), post.tags());
    }

    /** 移出公域：同上，走 TIMELINE_REMOVE。 */
    private void publishRemove(String mediaId, long userId, String timelineKey) {
        long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_REMOVE, mediaId, userId,
                new TimelineRemovePayload(timelineKey));
        outboxService.deliverAfterCommit(eventId);
    }

    private static boolean isHighRisk(String reason) {
        if (reason == null) return false;
        return reason.contains("涉政") || reason.contains("暴恐")
                || reason.contains("儿童") || reason.contains("未成年") || reason.contains("色情儿童");
    }

    /** 从 mediaId（media/{userId}/{uuid}.{ext}）解析归属 userId，用于审核接口分片路由。 */
    private static long parseUserId(String mediaId) {
        String[] parts = mediaId.split("/");
        if (parts.length < 2) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的 mediaId: " + mediaId);
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mediaId 中的 userId 非法: " + mediaId);
        }
    }

    /**
     * 失效<b>整帖</b>的状态缓存（旁路缓存 fail-open）。
     *
     * <p>状态缓存按 {@code mediaId} 分键（{@code tf:media:status:{mediaId}}），而状态翻转是整帖的：
     * 只清代表行会让同帖其余行在 60s TTL 内继续返回旧状态——「我的内容」若按任意行读状态就可能
     * 显示「审核中」而公域已可见。故这里把帖内全部 mediaId 的缓存一并清掉。</p>
     */
    private void evictCache(String mediaId, long userId) {
        try {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(STATUS_KEY_PREFIX + mediaId);
            String postId = mediaRepository.findPostId(mediaId, userId);
            if (postId != null && !postId.isBlank()) {
                for (MediaItem row : mediaRepository.listPostImages(userId, postId)) {
                    keys.add(STATUS_KEY_PREFIX + row.mediaId());
                }
            }
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }
}
