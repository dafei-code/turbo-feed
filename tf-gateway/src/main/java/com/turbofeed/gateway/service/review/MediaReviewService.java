package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.repository.AppealRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.event.outbox.TimelineAppendPayload;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.gateway.service.review.credit.ReporterCreditService;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.ModerationPolicy;
import com.turbofeed.gateway.service.review.ModerationScenario;
import com.turbofeed.gateway.service.review.ModerationVerdict;
import com.turbofeed.gateway.service.review.ContentModerationRouter;
import com.turbofeed.gateway.service.review.ReviewProperties;
import com.turbofeed.gateway.service.review.ReviewWaterLevelService;
import com.turbofeed.gateway.service.review.StrictnessProfile;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 媒体审核服务（门面，#310 已拆分为三个协作子服务）：维护内容审核状态机，并执行
 * 「机审初筛 + 账号信用分级（先发/先审）+ 多池发布 + 举报/申诉闭环」。
 *
 * <p><b>职责边界（#310 拆分）</b>：本类只保留<b>状态机门面</b>与<b>对外 API 契约</b>
 * （13 个 public 方法、{@code @Transactional} 边界、CAS 翻转语义），把三簇内聚逻辑下沉为无状态协作 Bean：</p>
 * <ul>
 *   <li>{@link ReviewTimelineDelivery}：审核状态 ↔ 公域时间线可见性（入流/摘除/缓存失效/整帖解析）；</li>
 *   <li>{@link ViolationDispositionService}：确认违规 → 梯度处置（MONITOR 限公域 / INTERCEPT 下架+扣分+落处罚）；</li>
 *   <li>{@link ReviewEscalationService}：抖音式「越火审得越严」「流量池晋级加严」「同类举报累计→人工复审」。</li>
 * </ul>
 * <p>所有子服务方法都在本门面的事务内执行，自身不声明事务，保证发件箱事件行与状态翻转同事务
 * （changelog 0037 / P0-3）。本次拆分<b>对外行为零回归</b>：方法签名、事务边界、CAS 幂等语义完全保留。</p>
 *
 * <p><b>抖音式分层</b>：上传后先跑机审初筛（{@link ContentModerationRouter}，MVP=本地规则引擎 RULE）；
 * 机审通过的内容按<b>账号信用等级</b>分流——L0 低信用/新号<b>先审后放</b>，L1/L2 高信用<b>先发后审</b>，
 * 机审 REJECTED 立即拦截。已发布内容可被用户举报、作者申诉，形成完整治理闭环。</p>
 *
 * <p><b>整帖一审（一帖多图）</b>：一次上传批次的 N 张图构成一个<b>帖子</b>（{@code post_id}），审核以<b>帖</b>为单位，
 * 机审一次、状态一次翻转、公域时间线一次投递。<b>整帖同上同下</b>——任一图被驳回即整帖驳回。</p>
 */
@Service
public class MediaReviewService {

    /**
     * 显式声明日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}）：
     * 本机构建环境对被修改文件的 Lombok 注解处理不生效，会报「log 找不到符号 / final 字段未初始化」。
     */
    private static final Logger log = LoggerFactory.getLogger(MediaReviewService.class);

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModerationRouter contentModeration;
    /** 审核域配置（{@code turbofeed.media.review}）。 */
    private final ReviewProperties reviewProperties;
    private final AccountCreditService accountCreditService;
    private final ReportRepository reportRepository;
    private final AppealRepository appealRepository;
    /** 复审任务队列（抖音式「举报累计→人工复审」，changelog 0065）。 */
    private final ReviewTaskRepository reviewTaskRepository;
    /** 发件箱（P0-3）：reviewByMediaId 直投路径仍内联同事务落事件（与子服务 publishAppend 同源语义）。 */
    private final OutboxService outboxService;
    /** 处罚域（penalty 集成缝）：申诉翻案解除封禁。 */
    private final PenaltyService penaltyService;
    /** 举报人信用（P1 #131）：举报处置后回写成立/驳回。 */
    private final ReporterCreditService reporterCreditService;
    /** 全局举报水位（P1 #132 动态阈值因子）：近窗举报总量 → 复审升级阈值浮动。 */
    private final ReviewWaterLevelService reviewWaterLevelService;
    /** 异常行为日志（P2-2）：举报行为持久化。 */
    private final BehaviorLogService behaviorLogService;
    /** 双严格度策略（feature-match M3）：按 (来源, 场景, 类目) 查表得阈值档案。 */
    private final ModerationPolicy moderationPolicy;
    /** 时间线投递子服务（#310）。 */
    private final ReviewTimelineDelivery delivery;
    /** 梯度处置子服务（#310）。 */
    private final ViolationDispositionService disposition;
    /** 晋级/举报升级子服务（#310）。 */
    private final ReviewEscalationService escalation;

    public MediaReviewService(MediaJdbcRepository mediaRepository,
                              StringRedisTemplate redisTemplate,
                              ContentModerationRouter contentModeration,
                              ReviewProperties reviewProperties,
                              AccountCreditService accountCreditService,
                              ReportRepository reportRepository,
                              AppealRepository appealRepository,
                              ReviewTaskRepository reviewTaskRepository,
                              OutboxService outboxService,
                              PenaltyService penaltyService,
                              ReporterCreditService reporterCreditService,
                              ReviewWaterLevelService reviewWaterLevelService,
                              BehaviorLogService behaviorLogService,
                              ModerationPolicy moderationPolicy,
                              ReviewTimelineDelivery delivery,
                              ViolationDispositionService disposition,
                              ReviewEscalationService escalation) {
        this.mediaRepository = mediaRepository;
        this.redisTemplate = redisTemplate;
        this.contentModeration = contentModeration;
        this.reviewProperties = reviewProperties;
        this.accountCreditService = accountCreditService;
        this.reportRepository = reportRepository;
        this.appealRepository = appealRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.outboxService = outboxService;
        this.penaltyService = penaltyService;
        this.reporterCreditService = reporterCreditService;
        this.reviewWaterLevelService = reviewWaterLevelService;
        this.behaviorLogService = behaviorLogService;
        this.moderationPolicy = moderationPolicy;
        this.delivery = delivery;
        this.disposition = disposition;
        this.escalation = escalation;
    }

    /**
     * 上传事件处理入口（本地 Spring 事件与 RocketMQ 消费者共用，审核逻辑单一来源）。
     *
     * <p>流程：整帖落库 PENDING → 机审初筛（整帖一次）→ 按账号信用分级决定先发后审 / 先审后放。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleUploaded(MediaUploadedEvent event) {
        long userId = Long.parseLong(event.userId());
        String representativeId = event.representativeMediaId();
        if (representativeId == null) {
            log.warn("收到不含任何媒体的上传事件，忽略: postId={}, userId={}", event.postId(), userId);
            return;
        }

        MediaStatus existing = mediaRepository.getStatus(representativeId, userId);
        if (existing != null && !MediaStatusMachine.isInitial(existing)) {
            // 重复投递：已是终态，幂等返回；APPROVED 兜底补齐时间线（按信用池）
            if (existing == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                delivery.publishAppend(representativeId, userId, delivery.toTimelinePost(event), level.poolLevel());
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
        ModerationVerdict verdict;
        try {
            verdict = contentModeration.verdict(representativeId, userId, urls.get(0));
        } catch (Exception e) {
            log.warn("机审初筛异常（fail-closed 强制人审）: postId={}, userId={}: {}",
                    event.postId(), userId, e.getMessage());
            verdict = new ModerationVerdict(MediaStatus.APPROVED, 0.0, List.of(), true);
        }

        if (reviewProperties.isAutoPass()) {
            // 演示占位：机审结果直接放行（仅供本地联调）
            ReviewOutcome outcome = review(representativeId, userId, verdict.decision() == MediaStatus.APPROVED);
            if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
                CreditLevel level = accountCreditService.ensure(userId);
                delivery.publishAppend(representativeId, userId, delivery.toTimelinePost(event), level.poolLevel());
            }
            return;
        }

        if (verdict.decision() == MediaStatus.REJECTED) {
            // 机审驳回即拦：整帖翻 REJECTED，不进人工队列
            review(representativeId, userId, false);
            log.info("机审驳回即拦：整帖判定 REJECTED（不进人审队列）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }

        // 梯度分流（M1 单阈值 → M3 双严格度矩阵）：中等把握 → 强制送人审（先审后放）
        StrictnessProfile profile = moderationPolicy.resolve(
                contentModeration.getActiveMode(), ModerationScenario.PUBLIC_FEED, null);
        if (verdict.needHumanScan() || verdict.confidence() < profile.getPassThreshold()) {
            log.info("机审中等把握（confidence={}, needHumanScan={}, strictness={}, passThreshold={}）→ 强制人审队列（先审后放）: postId={}, userId={}",
                    verdict.confidence(), verdict.needHumanScan(),
                    contentModeration.getActiveMode(), profile.getPassThreshold(), event.postId(), userId);
            return;
        }

        // 高置信度通过：按账号信用分级分流
        CreditLevel level = accountCreditService.ensure(userId);
        if (level == CreditLevel.L0) {
            log.info("低信用账户：机审通过转人审队列（先审后放，整帖）: postId={}, images={}, userId={}",
                    event.postId(), event.imageCount(), userId);
            return;
        }
        // 先发后审（L1/L2）：整帖直接 APPROVED 进对应流量池，靠举报/人审兜底
        ReviewOutcome outcome = review(representativeId, userId, true);
        if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
            delivery.publishAppend(representativeId, userId, delivery.toTimelinePost(event), level.poolLevel());
        }
    }

    /**
     * 审核动作的结果：{@code status} 是本次调用结束后帖子所处的状态；{@code transitioned} 表示
     * <b>本次调用</b>是否真正引起了状态流转（区分它才能避免同帖被重复投递公域时间线）。
     */
    public record ReviewOutcome(MediaStatus status, boolean transitioned) {
    }

    /**
     * 执行审核动作：PENDING -> APPROVED / REJECTED（终态），<b>作用于整帖</b>。
     *
     * <p>采用 CAS（带前置态）而非「读后直写」：并发双写收敛为「一次生效 + 一次幂等返回」，
     * 且加锁范围收窄到真正需要改的行，消除上传异步先发后审与管理员人审间的 InnoDB 死锁。
     * CAS 落空不是错误——本次什么都没改、不重复投递时间线，回读真实状态后原样返回，对调用方幂等成功。</p>
     */
    public ReviewOutcome review(String mediaId, long userId, boolean approved) {
        MediaStatus current = mediaRepository.getStatus(mediaId, userId);
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未找到待审核记录: mediaId=" + mediaId);
        }
        MediaStatus target = approved ? MediaStatus.APPROVED : MediaStatus.REJECTED;
        if (!MediaStatusMachine.isLegal(current, target)) {
            log.info("审核终态幂等返回（不重复流转）: mediaId={}, status={}, target={}", mediaId, current, target);
            return new ReviewOutcome(current, false);
        }
        int rows = mediaRepository.updateStatusCas(mediaId, userId, current, target);
        if (rows == 0) {
            MediaStatus actual = mediaRepository.getStatus(mediaId, userId);
            log.info("审核 CAS 落空（已被其他路径流转，本次幂等返回）: mediaId={}, expected={}, actual={}",
                    mediaId, current, actual);
            return new ReviewOutcome(actual == null ? current : actual, false);
        }
        delivery.evictCache(mediaId, userId);
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
     * 新人观察期计数 + <b>发件箱事件行</b>」原子提交。事件行先随事务落库（事务提交则消息必然存在，
     * 不会丢；事务回滚则消息一并消失，不会发出幽灵消息），提交后由 {@link OutboxService#deliverAfterCommit}
     * 直投一次保证低延迟，失败则由 {@code OutboxRelay} 轮询重投（至少一次 + 消费端幂等）。</p>
     *
     * <p><b>注</b>：本路径沿用改造前 inline 落发件箱事件（不含内容标签冷存写，与子服务
     * {@link ReviewTimelineDelivery#publishAppend} 一致语义、仅标签冷存为原差异），保持零行为回归。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public MediaStatus reviewByMediaId(String mediaId, boolean approved) {
        long userId = ReviewTimelineDelivery.parseUserId(mediaId);
        ReviewOutcome outcome = review(mediaId, userId, approved);
        if (outcome.transitioned() && outcome.status() == MediaStatus.APPROVED) {
            MediaItem post = delivery.asTimelinePost(mediaRepository.findMedia(mediaId, userId), userId);
            if (post != null) {
                CreditLevel level = accountCreditService.ensure(userId);
                long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_APPEND, mediaId, userId,
                        new TimelineAppendPayload(post, level.poolLevel()));
                outboxService.deliverAfterCommit(eventId);
            }
            // 人工通过计数：新人观察期据此解除。只统计人工路径——先发后审的自动通过不计入。
            accountCreditService.onHumanApproved(userId, reviewProperties.getNewUserApproveThreshold());
        }
        return outcome.status();
    }

    /**
     * 人工审核队列：全平台 PENDING 内容（按受理时间倒序，分页）。
     *
     * <p><b>为什么落在 Service 而不是让 Controller 直连仓储</b>：架构门禁禁止 Controller 直接依赖 Repository，
     * 横切逻辑（事务边界、权限校验、缓存失效）一旦可以在两层各写一遍，就会出现静默失效。本方法因此成为
     * 审核队列的<b>唯一入口</b>。</p>
     *
     * <p><b>性能提醒</b>：底层是跨分片广播查询，只适合管理端低流量的审核队列；公域读路径走引擎时间线读模型。</p>
     */
    public List<MediaItem> listPendingQueue(int limit, long offset) {
        return mediaRepository.listPendingGlobal(limit, offset);
    }

    /**
     * 用户举报：仅已发布内容可被举报。高危类目（涉政/暴恐/儿童）立即下架停推（fail-closed）；
     * 普通举报写表进人工队列，由 {@link #handleReport} 复核。下架作用于<b>整帖</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public void report(String mediaId, long reporterUserId, String reason, ViolationCategory category) {
        long authorId = ReviewTimelineDelivery.parseUserId(mediaId);
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur == null || cur != MediaStatus.APPROVED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅已发布内容可被举报");
        }
        // 恶意举报防御①：同举报人对同一内容重复举报只计一次
        if (reportRepository.existsPending(mediaId, reporterUserId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "您已举报过该内容，请勿重复举报");
        }
        // 恶意举报防御②：每举报人窗口限速（fail-open，不影响主流程）
        checkReporterRateLimit(reporterUserId);
        reportRepository.insert(mediaId, reporterUserId, reason, category.code());
        reporterCreditService.recordReport(reporterUserId);
        reviewWaterLevelService.recordReport();
        behaviorLogService.record(reporterUserId, mediaId, "REPORT", "category=" + category.code() + ",reason=" + reason);
        if (isHighRisk(reason) || isHighRiskCategory(category)) {
            // CAS 保证「真正完成下架的那一次」才扣信用+落处罚；其余并发举报得 0 行幂等返回。
            int rows = disposition.applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, "SYSTEM",
                    "高危举报立即下架停推: " + mediaId, ViolationSeverity.HIGH);
            if (rows == 0) {
                log.info("高危举报下架 CAS 落空（已被其他路径下架，不重复扣信用）: mediaId={}", mediaId);
            } else {
                log.warn("高危举报立即下架停推（整帖）: mediaId={}, reporterUserId={}, reason={}",
                        mediaId, reporterUserId, reason);
            }
            return;
        }
        // 普通举报：累计达阈值 → 自动建复审任务（抖音式「举报累计→人工复核」）
        escalation.maybeEscalateToReviewTask(mediaId, authorId);
    }

    /** 管理员处理举报：确认违规→整帖 TAKEN_DOWN + 扣信用；驳回→内容保持。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleReport(String mediaId, boolean confirmed) {
        long authorId = ReviewTimelineDelivery.parseUserId(mediaId);
        // P1 #131：先取出本内容的待处理举报人，处置后回写其信用
        List<Long> reporters = reportRepository.findReporterUserIds(mediaId);
        if (confirmed) {
            // CAS 保证单次：把帖子翻下去的那一次才扣信用+落处罚；已被其它路径下架则得 0 行幂等。
            int rows = disposition.applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, "ADMIN",
                    "举报确认违规→整帖下架: " + mediaId, ViolationSeverity.MID);
            if (rows == 0) {
                log.info("举报确认违规：内容已不在已发布态，不重复下架/扣信用: mediaId={}", mediaId);
            }
        }
        reportRepository.resolve(mediaId, confirmed);
        // P1 #131：举报处置后回写举报人信用（成立加分 / 驳回扣分；恶意举报惩罚复用健康分通道）
        for (long uid : reporters) {
            if (confirmed) {
                reporterCreditService.onReportUpheld(uid);
            } else {
                reporterCreditService.onReportRejected(uid);
            }
        }
    }

    /**
     * REVIEWER 二次研判复审任务（抖音式「举报累计→人工复核」的承接动作）。
     *
     * <p>违规→整帖 TAKEN_DOWN + 扣信用 + 落处罚（与高危举报同处置力度）；无违规→维持发布。
     * 无论哪种，都一并清理该 media 的全部待处理举报与待复审任务。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void decideReviewTask(long taskId, boolean takedown, String resolver) {
        ReviewTask task = reviewTaskRepository.findById(taskId);
        if (task == null || task.status() != ReviewTask.STATUS_PENDING) {
            throw new BizException(ErrorCode.PARAM_ERROR, "复审任务不存在或已处置");
        }
        String mediaId = task.mediaId();
        long authorId = task.authorId();
        // #133 评论区异常巡查任务：仅标记已巡查、不下架内容、不回写举报人信用、不清理 report
        if (task.taskType().equals(ReviewTask.TYPE_COMMENT_ANOMALY)) {
            reviewTaskRepository.resolve(taskId, ReviewTask.STATUS_RESOLVED, resolver);
            log.info("评论区异常巡查任务处置（仅标记已巡查，不下架内容）: taskId={}, mediaId={}, resolver={}", taskId, mediaId, resolver);
            return;
        }
        // P1 #131：先取出本内容的待处理举报人，处置后回写其信用（须在 resolve 改 status 前取）
        List<Long> reporters = reportRepository.findReporterUserIds(mediaId);
        if (takedown) {
            // CAS 保证「真正完成翻转的那一次」才扣信用+落处罚；已被其它路径下架则幂等不双计。
            disposition.applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, resolver,
                    "复审任务确认违规(举报累计): " + mediaId, ViolationSeverity.MID);
            reviewTaskRepository.resolveAllForMedia(mediaId, ReviewTask.STATUS_TAKEDOWN, resolver);
            reportRepository.resolve(mediaId, true);
            log.info("复审任务确认违规→整帖下架: taskId={}, mediaId={}, resolver={}", taskId, mediaId, resolver);
        } else {
            reviewTaskRepository.resolveAllForMedia(mediaId, ReviewTask.STATUS_RESOLVED, resolver);
            reportRepository.resolve(mediaId, false);
            log.info("复审任务判定无违规，维持发布: taskId={}, mediaId={}, resolver={}", taskId, mediaId, resolver);
        }
        // P1 #131：复审处置后回写举报人信用（成立加分 / 驳回扣分）
        for (long uid : reporters) {
            if (takedown) {
                reporterCreditService.onReportUpheld(uid);
            } else {
                reporterCreditService.onReportRejected(uid);
            }
        }
    }

    /** REVIEWER 复审队列：按状态列出任务（默认 0=待复审）。 */
    public List<ReviewTask> listReviewTasks(int status) {
        return reviewTaskRepository.findByStatus(status);
    }

    /**
     * 行为上报侧的热度累计与复审触发（抖音式「越火审得越严」）。委托 {@link ReviewEscalationService}，
     * fail-open：绝不阻断用户的互动/浏览主流程。
     */
    public void onInteraction(String postId, String type) {
        escalation.onInteraction(postId, type);
    }

    /**
     * 流量池晋级加严（抖音式「流量池分级」第3档，changelog 0067）。委托 {@link ReviewEscalationService}，
     * fail-open：绝不阻断晋级主流程。
     */
    public void onPoolPromoted(String postId, int from, int to) {
        escalation.onPoolPromoted(postId, from, to);
    }

    /**
     * 作者申诉：仅被驳回/下架内容可申。整帖翻 APPEALING（暂不可见），写表进人工复核。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(String mediaId, long authorUserId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorUserId);
        // 合法前置态由状态机裁决（isLegal 对 null 返回 false，故「查不到」与「状态不对」同源拒绝）。
        if (!MediaStatusMachine.canAppeal(cur)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅被驳回/下架内容可申诉（当前状态: " + cur + "）");
        }
        int rows = mediaRepository.updateStatusCas(mediaId, authorUserId, cur, MediaStatus.APPEALING);
        if (rows == 0) {
            // 并发重复申诉：另一条请求已把帖子翻成 APPEALING（并写了自己的申诉单），本次幂等返回。
            log.info("申诉 CAS 落空（已被其他路径流转，不重复写申诉单）: mediaId={}, expected={}", mediaId, cur);
            return;
        }
        delivery.removeFromTimeline(mediaId, authorUserId); // 申诉中暂不可见
        appealRepository.insert(mediaId, authorUserId);
        log.info("作者申诉（整帖暂不可见）: mediaId={}, authorUserId={}", mediaId, authorUserId);
    }

    /** 管理员处理申诉：翻案→整帖 APPROVED 恢复公域（按信用池）+ 信用加回；维持→整帖 TAKEN_DOWN。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(String mediaId, boolean upheld) {
        long authorId = ReviewTimelineDelivery.parseUserId(mediaId);
        if (upheld) {
            // CAS 前置态为 APPEALING：只有真正完成「申诉中 → 已发布」的那一次才恢复公域与加信用。
            int rows = mediaRepository.updateStatusCas(mediaId, authorId, MediaStatus.APPEALING, MediaStatus.APPROVED);
            if (rows > 0) {
                MediaItem post = delivery.asTimelinePost(mediaRepository.findMedia(mediaId, authorId), authorId);
                if (post != null) {
                    CreditLevel level = accountCreditService.ensure(authorId);
                    // 与 reviewByMediaId 同一套路：同事务落事件 + 提交后直投（失败由中继补偿），并写内容标签冷存。
                    delivery.publishAppend(mediaId, authorId, post, level.poolLevel());
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
            // 同上：APPEALING -> TAKEN_DOWN，合法流转以 MediaStatusMachine 为准
            int rows = mediaRepository.updateStatusCas(mediaId, authorId, MediaStatus.APPEALING, MediaStatus.TAKEN_DOWN);
            log.info("申诉维持原状: mediaId={}, rows={}", mediaId, rows);
        }
        appealRepository.resolve(mediaId, upheld);
    }

    private static boolean isHighRisk(String reason) {
        if (reason == null) return false;
        return reason.contains("涉政") || reason.contains("暴恐")
                || reason.contains("儿童") || reason.contains("未成年") || reason.contains("色情儿童");
    }

    /** P1-1：类目级高危——涉政/色情类举报即立即下架停推（与 reason 文本高危词同力度）。 */
    private static boolean isHighRiskCategory(ViolationCategory category) {
        return category == ViolationCategory.CONTENT_POLITICS
                || category == ViolationCategory.CONTENT_PORN;
    }

    /**
     * 恶意举报防御②：每举报人窗口限速（Redis 固定窗口，fail-open）。
     *
     * <p>对举报人维度设「每小时最多 N 次」上限，超限直接拒 {@link BizException}（参数错误），
     * 防止单用户批量举报刷爆审核台；Redis 抖动仅记日志放行，绝不阻断举报主流程。</p>
     */
    private void checkReporterRateLimit(long reporterUserId) {
        try {
            String key = "tf:report:ratelimit:" + reporterUserId;
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(
                        reviewProperties.getReporterRateLimitWindowSeconds()));
            }
            if (n != null && n > reviewProperties.getReporterRateLimitPerHour()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "举报过于频繁，请稍后再试");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("举报频控检查失败（fail-open，不影响主流程）: reporterUserId={}, {}", reporterUserId, e.getMessage());
        }
    }
}
