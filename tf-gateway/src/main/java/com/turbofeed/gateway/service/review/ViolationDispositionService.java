package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.health.AccountHealthService;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.signal.ModerationSignalService;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.gateway.storage.MediaStorageClient;
import com.turbofeed.shared.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 梯度处置子服务（#310 从 {@link MediaReviewService} 拆出）：把「确认违规」统一收敛到
 * {@link #applyDisposition}，再分发到 {@link #softLimit}（MONITOR 限公域）/ {@link #takeDownAndPenalize}
 * （INTERCEPT 下架+扣分+落处罚）。
 *
 * <p><b>无状态协作 Bean</b>：方法与调用方（门面）同事务；下架时通过 {@link ReviewTimelineDelivery#removeFromTimeline}
 * 摘除公域（与投递同源键）。所有违规路径（高危举报 / 管理员确认 / 复审任务 / 流量池晋级复扫）经本服务，
 * 行为由 {@link DispositionPolicy} 矩阵裁决，默认解析为 INTERCEPT，<b>零行为回归</b>。</p>
 */
@Service
public class ViolationDispositionService {

    private static final Logger log = LoggerFactory.getLogger(ViolationDispositionService.class);

    /** 梯度处置标记键前缀（P0-a）：MONITOR 软处置写入 {@code tf:media:disposition:{postId}=MONITOR}，限公域。键为 postId。 */
    private static final String DISPOSITION_KEY_PREFIX = "tf:media:disposition:";

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final AccountHealthService accountHealthService;
    private final ModerationSignalService moderationSignalService;
    private final BehaviorLogService behaviorLogService;
    private final DispositionPolicy dispositionPolicy;
    private final AccountCreditService accountCreditService;
    private final PenaltyService penaltyService;
    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final ReviewTimelineDelivery delivery;

    public ViolationDispositionService(MediaJdbcRepository mediaRepository,
                                       StringRedisTemplate redisTemplate,
                                       AccountHealthService accountHealthService,
                                       ModerationSignalService moderationSignalService,
                                       BehaviorLogService behaviorLogService,
                                       DispositionPolicy dispositionPolicy,
                                       AccountCreditService accountCreditService,
                                       PenaltyService penaltyService,
                                       MediaProperties properties,
                                       MediaStorageClient storageClient,
                                       ReviewTimelineDelivery delivery) {
        this.mediaRepository = mediaRepository;
        this.redisTemplate = redisTemplate;
        this.accountHealthService = accountHealthService;
        this.moderationSignalService = moderationSignalService;
        this.behaviorLogService = behaviorLogService;
        this.dispositionPolicy = dispositionPolicy;
        this.accountCreditService = accountCreditService;
        this.penaltyService = penaltyService;
        this.properties = properties;
        this.storageClient = storageClient;
        this.delivery = delivery;
    }

    /**
     * 梯度处置统一入口（P0-a）：按 {@link DispositionPolicy} 把 (来源,严重度) 解析成处置动作后分发。
     *
     * <p>现有所有违规路径（高危举报/管理员确认/复审任务/流量池晋级复扫）都是 MID/HIGH，默认矩阵
     * 解析为 {@link Disposition#INTERCEPT}（=原下架行为），故本次改造<b>零行为回归</b>；
     * 仅有 LOW 命中或运维在 {@code disposition-matrix} 显式配置的来源:严重度会被降级为
     * {@link Disposition#MONITOR}（限公域/仅自己可见，不下架不扣分）。</p>
     *
     * <p><b>ADR-0014</b>：矩阵若被显式配成 {@link Disposition#DEMOTE}，走「只写内容信号」的
     * 轻降权分支——不扣健康分、不摘时间线、不落处罚；否则会被误当成 INTERCEPT 下架。
     * DEMOTE 不是默认矩阵的产物，因此不影响任何既有违规路径。</p>
     *
     * @return 受影响行数（MONITOR 软处置恒返回 1；INTERCEPT 的语义见 {@link #takeDownAndPenalize}）
     */
    int applyDisposition(String mediaId, long authorId, ViolationSource source,
                         String operator, String reason, ViolationSeverity severity) {
        Disposition d = dispositionPolicy.resolve(source, severity);
        // P2-1：把「内容处置」写成外供 KV（INTERCEPT/MONITOR/DEMOTE 都写）。
        String postId = mediaRepository.findPostId(mediaId, authorId);
        moderationSignalService.publishMediaDisposition(postId, d, severity, source);
        // 异常行为日志（P2-2）：处置行为归内容作者，供 P2-1 信号闭环来源（fail-open，不影响主流程）
        behaviorLogService.record(authorId, mediaId, "DISPOSITION", "severity=" + severity + ",source=" + source + ",action=" + d);

        // ADR-0014 防御分支：DEMOTE 是「内容级轻降权」，不是「确认违规」。
        // 默认处置矩阵不产出 DEMOTE，但运维可在 disposition-matrix 里显式配置；若不在此拦截，
        // 会落进下面的 takeDownAndPenalize → 把一条只需降权的内容直接下架+扣分+落处罚。
        // 这里只保留上面已写的内容信号：不扣健康分/信用、不摘时间线、不落处罚。
        if (d == Disposition.DEMOTE) {
            log.info("梯度处置 DEMOTE（内容级轻降权，不扣分不下架）: mediaId={}, source={}, reason={}",
                    mediaId, source, reason);
            return 1;
        }

        // P0-b：确认违规 → 扣健康分（MONITOR/INTERCEPT 都走这里，二者都是已确认违规）。
        // 与信用扣分、落处罚同触发点；三通道解耦，互不影响。
        int newScore = accountHealthService.recordViolation(authorId, severity);
        // P2-1：把「账号健康分」写成外供 KV（刷新式，上游推荐据此降权/过滤）。
        moderationSignalService.publishAccountHealth(authorId, newScore, healthTier(newScore));
        if (d == Disposition.MONITOR) {
            return softLimit(mediaId, authorId, source, reason);
        }
        // INTERCEPT（默认）：原有「下架+扣信用+落处罚」，行为不变。
        return takeDownAndPenalize(mediaId, authorId, source, operator, reason, severity);
    }

    /**
     * 解除该帖的内容处置信号（ADR-0014 / ADR-0015 解除闭环）。
     *
     * <p>用于「处置结论被推翻」的两种路径：{@code CONTENT_REVIEW} 人审判无违规（经
     * {@link #restoreAfterNoViolation} 复用本方法）、以及
     * {@code MediaReviewService#handleAppeal(upheld=true)} 申诉翻案。若不清，
     * MONITOR/INTERCEPT 会在 KV 默认 30 天 TTL 内一直被引擎召回层和搜索层硬剔除——
     * 表现为「审核/申诉通过了，内容却依然搜不到、进不了推荐」。</p>
     *
     * <p><b>为什么放在本服务而不是门面直接调信号服务</b>：门面
     * {@code MediaReviewService} 已被多个单测手动 {@code new}（19 参构造），再加一个依赖
     * 会让所有测试构造器编译断裂。本服务原本就持有 {@link ModerationSignalService} 与
     * {@code mediaRepository}，复用它们即可做到<b>零构造器变更</b>。</p>
     *
     * <p>fail-open：反查或删除异常只记日志不抛，不影响审核/申诉结论落库；
     * postId 为空（历史单图帖 {@code post_id=''}）直接跳过，避免误删同名前缀键。</p>
     */
    public void clearContentSignal(String mediaId, long authorId) {
        try {
            String postId = mediaRepository.findPostId(mediaId, authorId);
            if (postId == null || postId.isBlank()) {
                return;
            }
            moderationSignalService.clearMediaDisposition(postId);
            log.info("处置结论推翻→解除内容处置信号: mediaId={}, postId={}", mediaId, postId);
        } catch (Exception e) {
            log.warn("解除内容处置信号失败（fail-open，不影响审核结论）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }

    /**
     * 判定「无违规」后恢复该帖的公域可见性（ADR-0015，补齐 ADR-0014 §5 遗留项）。
     *
     * <p>{@link #softLimit} 曾把内容从公域时间线摘除（{@code removeFromTimeline}）并写下
     * {@code tf:media:disposition:{key}=MONITOR} 标记。此前人审判无违规只清了
     * {@code tf:mod:media} 信号，<b>没有重投时间线</b> → 搜索恢复可见、发现流仍缺该帖。
     * 本方法把「清信号 + 删标记 + 重投」收成一次幂等动作。</p>
     *
     * <p><b>幂等依据</b>：只有 {@code tf:media:disposition} 标记存在（确实被 softLimit 摘过）
     * 才重投；标记删除后重复调用不会二次 append。内容级 CONTENT_REVIEW 任务没有该标记，
     * 只清信号、不重投（时间线本来就在，此前只是被 MONITOR 硬剔除）。</p>
     *
     * <p><b>状态闸门</b>：仅 {@link MediaStatus#APPROVED} 才恢复。TAKEN_DOWN 的下架结论
     * 不能由一次「判无违规」推翻（那是申诉流程的职责），否则会把已下架内容重新灌回公域；
     * 该分支连信号都不清，保护 INTERCEPT 下架语义。</p>
     *
     * <p><b>任务类型闸门（零行为回归）</b>：ADR-0014 明确规定只有 {@code CONTENT_REVIEW}
     * 判无违规才解除 {@code tf:mod:media}。举报累计 / 热度 / 晋级复扫等任务判无违规时，
     * 若该帖同时还有<b>尚未判决</b>的内容审核隔离，贸然清信号会提前放行未审内容、误清
     * INTERCEPT。因此这里保留原语义：{@code taskType != CONTENT_REVIEW} 且没有 softLimit
     * 标记时，直接返回、不碰任何 Redis 键；只有「确实被摘过时间线」才走恢复闭环。</p>
     *
     * <p>调用方处于 {@code @Transactional} 内，{@code publishAppend} 的发件箱事件与本次
     * 处置落在同一事务，失败由 OutboxRelay 补偿重投。fail-open：任何异常只记日志并返回
     * {@code false}，不影响人审结论落库。</p>
     *
     * @param taskType 复审任务类型，用于保留 ADR-0014 的「只有 CONTENT_REVIEW 才清信号」语义
     * @return 是否真的重投了公域时间线
     */
    public boolean restoreAfterNoViolation(String mediaId, long authorId, String taskType) {
        try {
            MediaStatus status = mediaRepository.getStatus(mediaId, authorId);
            if (status != MediaStatus.APPROVED) {
                log.info("判无违规但内容非 APPROVED，不恢复公域: mediaId={}, status={}", mediaId, status);
                return false;
            }
            String postId = mediaRepository.findPostId(mediaId, authorId);
            String key = (postId == null || postId.isBlank()) ? mediaId : postId;
            String markerKey = DISPOSITION_KEY_PREFIX + key;
            String marker = redisTemplate.opsForValue().get(markerKey);
            boolean softLimited = Disposition.MONITOR.name().equals(marker);

            // 既不是内容审核任务、又没被 softLimit 摘过 → 不碰信号（保持 ADR-0014 原行为）。
            if (!softLimited && !ReviewTask.TYPE_CONTENT_REVIEW.equals(taskType)) {
                return false;
            }
            clearContentSignal(mediaId, authorId); // 复用同一解除能力（内部含 blank 闸门与 fail-open）
            if (!softLimited) {
                // CONTENT_REVIEW：时间线本来就在，此前只是被 MONITOR 硬剔除，清信号即可。
                return false;
            }
            MediaItem post = delivery.asTimelinePost(mediaRepository.findMedia(mediaId, authorId), authorId);
            if (post == null) {
                // 标记保留，便于补偿任务/下次判定重试；否则内容会永久停留在「摘除但无标记」的中间态
                return false;
            }
            CreditLevel level = accountCreditService.ensure(authorId);
            delivery.publishAppend(mediaId, authorId, post, level.poolLevel());
            // 标记必须最后删：Redis 删除不随 DB 事务回滚，若投递失败或事务回滚，先删标记会让
            // 这次恢复彻底不可重试（时间线缺帖 + 标记已消失）。保留标记则由 OutboxRelay/下次判定补偿。
            redisTemplate.delete(markerKey);
            log.info("判无违规→解除 MONITOR 软处置并重投公域时间线: mediaId={}, key={}", mediaId, key);
            return true;
        } catch (Exception e) {
            log.warn("恢复公域可见性失败（fail-open，不影响人审结论）: mediaId={}, {}", mediaId, e.getMessage());
            return false;
        }
    }

    /** P2-1 健康分梯级（与 P0-b 阶梯一致，给上游推荐直接判定用）：NORMAL 正常 / DEMOTE 降权 / RESTRICT_SUBMIT 限投稿 / RESTRICT_MONETIZE 限变现 / BANNED 封禁。 */
    private static String healthTier(int score) {
        if (score >= 80) {
            return "NORMAL";
        }
        if (score >= 60) {
            return "DEMOTE";
        }
        if (score >= 40) {
            return "RESTRICT_SUBMIT";
        }
        if (score > 0) {
            return "RESTRICT_MONETIZE";
        }
        return "BANNED";
    }

    /**
     * 软处置（MONITOR，网关独做）：限公域 / 仅自己可见。
     *
     * <p>对齐抖音「先是流量隔离，而非删除」——内容<b>移出推荐公域</b>（{@code removeFromTimeline} 走
     * outbox 摘除），作者仍可在 {@code media/mine} 自见（该视图按作者查，不经公域时间线）；
     * 帖子状态保持 {@code APPROVED}，<b>不扣信用、不落处罚</b>。写入 {@code tf:media:disposition:{postId}}
     * 标记，供运营/后续 backfill 识别。</p>
     *
     * <p><b>已知边界（v1 网关独做，不改 feed-engine）</b>：backfill 是引擎侧批量重投，当前不读该标记，
     * 理论上 admin 手动全量 backfill 可能把 MONITOR 内容重新灌回公域。常见路径（审核通过/互动）不会触发，
     * 属低频边界；引擎侧在 backfill 时读 {@code tf:media:disposition:{postId}} 跳过即可彻底闭环
     * （低成本，留待确需时补）。</p>
     */
    private int softLimit(String mediaId, long authorId, ViolationSource source, String reason) {
        String postId = mediaRepository.findPostId(mediaId, authorId);
        String key = (postId == null || postId.isBlank()) ? mediaId : postId;
        try {
            redisTemplate.opsForValue().set(DISPOSITION_KEY_PREFIX + key, Disposition.MONITOR.name());
        } catch (Exception e) {
            log.warn("写入处置标记失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
        delivery.removeFromTimeline(mediaId, authorId); // 移出公域（outbox TIMELINE_REMOVE），作者仍自见
        log.warn("梯度处置 MONITOR（限公域/仅自己可见，不下架不扣分）: mediaId={}, source={}, reason={}",
                mediaId, source, reason);
        return 1;
    }

    /**
     * 把整帖从 APPROVED 翻 TAKEN_DOWN + 移出公域 + 扣信用 + 落处罚（高危举报 / 管理员确认 / 复审任务共用）。
     *
     * <p>CAS 保证「真正完成翻转的那一次」才扣信用/落处罚（并发或重复处置幂等，不双计）。
     * 返回受影响行数（0 表示内容已被其它路径下架，本次不重复处置）。</p>
     */
    private int takeDownAndPenalize(String mediaId, long authorId, ViolationSource source,
                                    String operator, String reason, ViolationSeverity severity) {
        // APPROVED -> TAKEN_DOWN：合法流转见 MediaStatusMachine（CAS 的 expected 必须与表一致，
        // 由 MediaStatusMachineTest 的「CAS 流转对一致性」用例反向锁定，避免两处各写一遍后漂移）。
        int rows = mediaRepository.updateStatusCas(mediaId, authorId, MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN);
        if (rows > 0) {
            delivery.removeFromTimeline(mediaId, authorId);
            accountCreditService.onViolationConfirmed(authorId);
            // penalty 集成缝：确认违规 → 落处罚（与信用扣分同触发点，CAS 保证单次）。
            penaltyService.recordViolation(authorId, ViolationCategory.OTHER, severity,
                    source, mediaId, operator, reason);
            // P0-3：下架同步物理删除对象（minio 私有桶下杜绝直链残留；本地/占位不删，保留申诉恢复可能）
            if ("minio".equals(properties.getStorage())) {
                storageClient.delete(mediaId);
            }
        }
        return rows;
    }
}
