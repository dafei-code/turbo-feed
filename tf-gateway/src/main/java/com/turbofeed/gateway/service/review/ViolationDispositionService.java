package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
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
     * @return 受影响行数（MONITOR 软处置恒返回 1；INTERCEPT 的语义见 {@link #takeDownAndPenalize}）
     */
    int applyDisposition(String mediaId, long authorId, ViolationSource source,
                         String operator, String reason, ViolationSeverity severity) {
        // P0-b：确认违规 → 扣健康分（MONITOR/INTERCEPT 都走这里，二者都是已确认违规）。
        // 与信用扣分、落处罚同触发点；三通道解耦，互不影响。
        int newScore = accountHealthService.recordViolation(authorId, severity);
        // P2-1：把「账号健康分」写成外供 KV（刷新式，上游推荐据此降权/过滤）。
        moderationSignalService.publishAccountHealth(authorId, newScore, healthTier(newScore));

        Disposition d = dispositionPolicy.resolve(source, severity);
        // P2-1：把「内容处置」写成外供 KV（MONITOR/INTERCEPT 都写，二者都是已确认违规）。
        String postId = mediaRepository.findPostId(mediaId, authorId);
        moderationSignalService.publishMediaDisposition(postId, d, severity, source);
        // 异常行为日志（P2-2）：处置行为归内容作者，供 P2-1 信号闭环来源（fail-open，不影响主流程）
        behaviorLogService.record(authorId, mediaId, "DISPOSITION", "severity=" + severity + ",source=" + source + ",action=" + d);
        if (d == Disposition.MONITOR) {
            return softLimit(mediaId, authorId, source, reason);
        }
        // INTERCEPT（默认）：原有「下架+扣信用+落处罚」，行为不变。
        return takeDownAndPenalize(mediaId, authorId, source, operator, reason, severity);
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
