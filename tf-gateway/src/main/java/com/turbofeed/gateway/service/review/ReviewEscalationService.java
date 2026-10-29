package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.credit.ReporterCreditService;
import com.turbofeed.gateway.service.review.ModerationVerdict;
import com.turbofeed.gateway.service.review.ContentModerationRouter;
import com.turbofeed.gateway.service.review.ReviewProperties;
import com.turbofeed.gateway.service.review.ReviewWaterLevelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 晋级 / 举报升级子服务（#310 从 {@link MediaReviewService} 拆出）：负责抖音式「越火审得越严」「流量池晋级加严」
 * 与「同类举报累计 → 人工复审」的触发逻辑。
 *
 * <p><b>无状态协作 Bean</b>：方法与调用方（门面）同事务或 fail-open 调用；晋级复扫命中违规时委托
 * {@link ViolationDispositionService#applyDisposition} 走统一处置，保证与高危举报/管理员确认同力度。</p>
 */
@Service
public class ReviewEscalationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewEscalationService.class);

    /** 热度累计计数键前缀（抖音式「越火审得越严」，changelog 0066）。键为 postId（帖身份）。 */
    private static final String HEAT_KEY_PREFIX = "tf:media:heat:";

    /** 流量池分级热度阈值覆盖键前缀（Tier3，changelog 0067）：按池级收紧该帖热度复审阈值。键为 postId。 */
    private static final String HEAT_THRESHOLD_OVERRIDE_PREFIX = "tf:media:heat-threshold:";

    /** 正向互动类型（行为上报 type 取值）：点赞/评论/转发，计作热度。 */
    private static final Set<String> POSITIVE_INTERACTIONS = Set.of("LIKE", "COMMENT", "SHARE");

    private final MediaJdbcRepository mediaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ContentModerationRouter contentModeration;
    private final ReviewProperties reviewProperties;
    private final ReportRepository reportRepository;
    private final ReporterCreditService reporterCreditService;
    private final ReviewWaterLevelService reviewWaterLevelService;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ViolationDispositionService disposition;

    public ReviewEscalationService(MediaJdbcRepository mediaRepository,
                                    StringRedisTemplate redisTemplate,
                                    ContentModerationRouter contentModeration,
                                    ReviewProperties reviewProperties,
                                    ReportRepository reportRepository,
                                    ReporterCreditService reporterCreditService,
                                    ReviewWaterLevelService reviewWaterLevelService,
                                    ReviewTaskRepository reviewTaskRepository,
                                    ViolationDispositionService disposition) {
        this.mediaRepository = mediaRepository;
        this.redisTemplate = redisTemplate;
        this.contentModeration = contentModeration;
        this.reviewProperties = reviewProperties;
        this.reportRepository = reportRepository;
        this.reporterCreditService = reporterCreditService;
        this.reviewWaterLevelService = reviewWaterLevelService;
        this.reviewTaskRepository = reviewTaskRepository;
        this.disposition = disposition;
    }

    /**
     * 行为上报侧的热度累计与复审触发（抖音式「越火审得越严」，changelog 0066）。
     *
     * <p>对正向互动（{@code LIKE/COMMENT/SHARE}）累加 Redis 计数 {@code tf:media:heat:{postId}}；
     * 当计数<b>恰好跨过</b>阈值、且内容仍 {@code APPROVED}、且无未决复审任务时，
     * 自动建一条 {@code HEAT_ACCUMULATED} 复审任务归 REVIEWER 二次研判（复用第 1 档队列与
     * {@link MediaReviewService#decideReviewTask}）。</p>
     *
     * <p><b>fail-open</b>：本方法绝不阻断用户的互动/浏览主流程——任何异常（Redis 抖动、DB 解析失败）
     * 都仅记日志并返回，不影响事件发布。</p>
     *
     * <p><b>为什么只在「恰好跨过阈值」时触发</b>：用 {@code INCR} 返回值与阈值<b>相等</b>才建单，
     * 保证一次病毒式走红只触发一次复审（并发 INCR 也至多一个请求命中阈值，其余得 {@code >} 阈值），
     * 避免重复建单；任务维持发布后计数已 {@code >} 阈值，后续互动不再触发（除非内容被下架后又恢复、
     * 计数重新逼近阈值），契合「一次走红、一次加严复审」语义，无需额外状态。</p>
     */
    public void onInteraction(String postId, String type) {
        if (postId == null || postId.isBlank() || type == null) {
            return;
        }
        if (!POSITIVE_INTERACTIONS.contains(type.toUpperCase(Locale.ROOT))) {
            return; // 仅正向互动计入热度（曝光/完播/不感兴趣等不触发）
        }
        try {
            String key = HEAT_KEY_PREFIX + postId;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return;
            }
            // 流量池分级（changelog 0067）：优先读该帖的池级覆盖阈值（越高池越低），无覆盖则回落全局默认。
            int threshold = heatThresholdFor(postId);
            if (count == threshold) {
                maybeEscalateToReviewTaskByHeat(postId, count.intValue());
            }
        } catch (Exception e) {
            log.warn("热度累计/复审触发失败（不影响互动主流程）: postId={}, type={}, {}", postId, type, e.getMessage());
        }
    }

    /**
     * 该帖热度复审阈值：若有流量池分级写入的覆盖值（{@code tf:media:heat-threshold:{postId}}）则用之，
     * 否则回落全局默认 {@code heatReReviewThreshold}。fail-open：读失败即回落默认，绝不阻断互动主流程。
     */
    private int heatThresholdFor(String postId) {
        try {
            String ov = redisTemplate.opsForValue().get(HEAT_THRESHOLD_OVERRIDE_PREFIX + postId);
            if (ov != null && !ov.isBlank()) {
                return Integer.parseInt(ov);
            }
        } catch (Exception e) {
            log.warn("读取流量池热度阈值覆盖失败，回落全局默认: postId={}, {}", postId, e.getMessage());
        }
        return reviewProperties.getHeatReReviewThreshold();
    }

    /**
     * 热度达阈值 → 自动建一条 {@code HEAT_ACCUMULATED} 复审任务（抖音式「越火审得越严」）。
     *
     * <p>行为上报只携带 {@code postId}（帖身份），本方法先把它解析回代表行
     * {@code media_id} + 归属 {@code authorId}（{@link MediaJdbcRepository#findRepresentativeByPostId}），
     * 再仅在内容仍已发布、且无未决任务时建单；REVIEWER 在 {@link MediaReviewService#decideReviewTask} 二次研判。</p>
     */
    private void maybeEscalateToReviewTaskByHeat(String postId, int heat) {
        MediaJdbcRepository.PostRepresentative rep = mediaRepository.findRepresentativeByPostId(postId);
        if (rep == null || rep.status() != MediaStatus.APPROVED) {
            return; // 帖不存在或已不在公域（下架/申诉中），不再建复审任务
        }
        if (reviewTaskRepository.existsOpenForMedia(rep.mediaId())) {
            return; // 已有待复审任务，避免重复建单
        }
        reviewTaskRepository.insert(rep.mediaId(), rep.authorId(), ReviewTask.TYPE_HEAT_ACCUMULATED, heat);
        log.info("热度累计达阈值，自动建复审任务(HEAT_ACCUMULATED，REVIEWER 二次研判): postId={}, mediaId={}, heat={}, threshold={}",
                postId, rep.mediaId(), heat, reviewProperties.getHeatReReviewThreshold());
    }

    /**
     * 流量池晋级加严（抖音式「流量池分级」第3档，changelog 0067）。
     *
     * <p>引擎在内容晋级到更高池时回调本方法，执行<b>三件套</b>加严：</p>
     * <ol>
     *   <li><b>机审复扫</b>：对已发布内容重新跑 {@link ContentModerationRouter#moderate}（fail-open）；
     *       命中 {@code REJECTED} → 整帖 {@code TAKEN_DOWN} + 扣信用 + 落处罚
     *       （{@code ViolationSeverity#HIGH}，与高危举报同力度）。已发布内容走红后若被机审初筛漏过，
     *       这是「越火越要复核」的关键兜底（fail-closed：宁下勿漏）。</li>
     *   <li><b>建 {@code POOL_PROMOTED} 复审任务</b>：每次晋级都建一条归 REVIEWER 二次研判
     *       （「晋级必过人审」），仅当内容仍已发布且无未决任务时建单，避免重复建单。</li>
     *   <li><b>按池级收紧该帖热度阈值（越火审得越严）</b>：写入
     *       {@code tf:media:heat-threshold:{postId} = poolHeatThreshold(to)}，
     *       {@link #onInteraction} 累计热度时优先读该覆盖值，使更高池更快触发 {@code HEAT_ACCUMULATED}。</li>
     * </ol>
     *
     * <p><b>fail-open</b>：本方法绝不阻断晋级主流程——任何异常仅记日志返回，引擎侧的晋级已独立完成。
     * 机审复扫命中下架后不再建人审任务（避免重复处置同一内容）。</p>
     */
    public void onPoolPromoted(String postId, int from, int to) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            MediaJdbcRepository.PostRepresentative rep = mediaRepository.findRepresentativeByPostId(postId);
            if (rep == null || rep.status() != MediaStatus.APPROVED) {
                // 帖不存在或已不在公域（下架/申诉中）：不再加严
                log.info("流量池晋级加严跳过（内容不在已发布态）: postId={}, {}",
                        postId, rep == null ? "rep=null" : rep.status());
                return;
            }
            // ① 机审复扫（fail-open）：已发布走红内容必须再核一遍
            ModerationVerdict verdict = contentModeration.verdict(rep.mediaId(), rep.authorId(), rep.url());
            if (verdict.decision() == MediaStatus.REJECTED) {
                int rows = disposition.applyDisposition(rep.mediaId(), rep.authorId(), ViolationSource.POOL_PROMOTED, "SYSTEM",
                        "流量池晋级机审复扫命中违规(已发布走红内容): " + postId, ViolationSeverity.HIGH);
                if (rows == 0) {
                    log.info("流量池晋级复扫下架 CAS 落空（已被其它路径下架，不重复处置）: postId={}", postId);
                } else {
                    log.warn("流量池晋级机审复扫命中→整帖下架停推: postId={}, mediaId={}", postId, rep.mediaId());
                }
                return; // 已下架，不再建人审任务（避免重复处置）
            }
            // ② 建 POOL_PROMOTED 复审任务（晋级必过人审）+ ③ 收紧该帖热度阈值
            maybeEscalateAndTightenByPool(postId, rep.mediaId(), rep.authorId(), to);
        } catch (Exception e) {
            log.warn("流量池晋级加严处理失败（fail-open，不影响晋级主流程）: postId={}, {}→{}: {}",
                    postId, from, to, e.getMessage());
        }
    }

    /**
     * 流量池晋级加严（人审任务 + 阈值收紧）：仅当内容仍已发布、无未决复审任务时建 {@code POOL_PROMOTED} 任务；
     * 同时写入该帖按池级收紧的热度阈值覆盖值（L1=1000/L2=500/L3=200，见 {@link #poolHeatThreshold}）。
     *
     * <p>覆盖键以 {@code postId} 为维度——与 {@link #onInteraction} 的热度计数键同源，保证读回的是同一帖。</p>
     */
    private void maybeEscalateAndTightenByPool(String postId, String mediaId, long authorId, int to) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur != MediaStatus.APPROVED) {
            return; // 已不在公域，不再建复审任务
        }
        if (!reviewTaskRepository.existsOpenForMedia(mediaId)) {
            reviewTaskRepository.insert(mediaId, authorId, ReviewTask.TYPE_POOL_PROMOTED, to);
            log.info("流量池晋级→建复审任务(POOL_PROMOTED，REVIEWER 二次研判): postId={}, mediaId={}, to={}",
                    postId, mediaId, to);
        }
        // ③ 收紧该帖热度阈值（越火审得越严）：写覆盖值，onInteraction 优先读它
        int threshold = poolHeatThreshold(to);
        try {
            redisTemplate.opsForValue().set(HEAT_THRESHOLD_OVERRIDE_PREFIX + postId, Integer.toString(threshold));
        } catch (Exception e) {
            log.warn("写入流量池热度阈值覆盖失败（不影响主流程）: postId={}, to={}, threshold={}, {}",
                    postId, to, threshold, e.getMessage());
        }
    }

    /** 流量池分级热度复审阈值：池越高阈值越低（越火审得越严）。L1=1000/L2=500/L3=200；兜底用全局默认。 */
    private int poolHeatThreshold(int pool) {
        ReviewProperties r = reviewProperties;
        return switch (pool) {
            case 1 -> r.getPoolHeatThresholdL1();
            case 2 -> r.getPoolHeatThresholdL2();
            case 3 -> r.getPoolHeatThresholdL3();
            default -> r.getHeatReReviewThreshold();
        };
    }

    /**
     * 同类举报累计达阈值 → 自动建一条复审任务（抖音式「举报分类 → 同类累计才升级」，P1-1）。
     *
     * <p>仅在该内容仍处于已发布态、且无未决复审任务时建单。阈值判定改为<b>按类目</b>：
     * 取各类目待处理举报数的最大值，仅当<b>某一类目</b>累计达到阈值才升级——
     * 避免「1 色情 + 1 广告 + 1 辱骂」这类无关举报混加误爆审核台（抖音式「同类举报才可信」）。</p>
     */
    void maybeEscalateToReviewTask(String mediaId, long authorId) {
        MediaStatus cur = mediaRepository.getStatus(mediaId, authorId);
        if (cur != MediaStatus.APPROVED) {
            return; // 已不在公域（下架/申诉中），不再建复审任务
        }
        // #132 全局举报水位因子：阈值随近窗举报总量浮动（爆量收紧/平稳放宽），与 #131 信用过滤互补
        int threshold = reviewWaterLevelService.currentReportThreshold();
        // P1 #131：取全部待处理举报的 (举报人,类目)，仅统计信用达标举报人（低信用举报不计入升级）
        Map<Integer, Integer> byCategory = new HashMap<>();
        for (ReportRepository.PendingReport p : reportRepository.pendingByMedia(mediaId)) {
            if (!reporterCreditService.isCredible(p.reporterUserId())) {
                continue; // 低信用举报：不计入复审升级（防恶意刷台）
            }
            byCategory.merge(p.category(), 1, Integer::sum);
        }
        int maxCount = 0;
        int topCategory = 0;
        for (Map.Entry<Integer, Integer> e : byCategory.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                topCategory = e.getKey();
            }
        }
        if (maxCount < threshold) {
            return;
        }
        if (reviewTaskRepository.existsOpenForMedia(mediaId)) {
            return; // 已有待复审任务，避免重复建单
        }
        reviewTaskRepository.insert(mediaId, authorId, ReviewTask.TYPE_REPORT_ACCUMULATED, maxCount);
        log.info("同类举报累计达阈值，自动建复审任务（仅信用达标举报人，REVIEWER 二次研判）: mediaId={}, category={}, count={}, threshold={}",
                mediaId, topCategory, maxCount, threshold);
    }
}
