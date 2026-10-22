package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.shared.caption.CaptionTagParser;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.event.MediaEventPublisher;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.idempotency.UploadIdempotency;
import com.turbofeed.gateway.service.health.AccountHealth;
import com.turbofeed.gateway.service.health.AccountHealthService;
import com.turbofeed.gateway.service.presign.UploadReservation;
import com.turbofeed.gateway.service.presign.UploadReservationStore;
import com.turbofeed.gateway.service.processing.ImageProcessingChain;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.service.review.RiskScorer;
import com.turbofeed.gateway.service.review.ReviewTask;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 预签名直传的<b>异步收尾</b>：对象已由客户端直传到位、且通过服务端复检之后才轮到本类。
 *
 * <p><b>为什么单独成类而不是并进 {@link MediaUploadService}</b>：收尾跑在
 * {@code uploadFinalizeExecutor} 线程上，而 {@code @Async} 靠代理生效——
 * 必须由<b>另一个 Bean</b> 调用才走代理。若把 {@code @Async} 标在
 * {@code MediaUploadService} 自己的方法上，同类内部调用会绕过代理、退化为同步执行，
 * 上传线程反而被落库拖住（Spring 自调用失效的经典坑）。</p>
 *
 * <p><b>为什么不再读线程上下文</b>：异步线程没有 JWT 的 UserContext（ThreadLocal 不跨线程），
 * 故一切身份/文案信息都从 {@link UploadReservation} 取——那是在 presign 阶段由请求线程
 * 落下的、且已完成越权校验的快照。</p>
 *
 * <p><b>失败语义</b>：收尾失败即整帖作废——删除已传对象 + 物理删除可能已落的残行，
 * 与 {@code MediaUploadService#cleanupPartialPost} 同一口径（绝不留「缺代表行 / 张数不齐」
 * 的半成品帖）。清理动作本身失败只告警，不掩盖原始异常。</p>
 */
@Service
public class MediaUploadFinalizer {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadFinalizer.class);

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final ImageProcessingChain processingChain;
    private final MediaJdbcRepository mediaRepository;
    private final MediaEventPublisher eventPublisher;
    private final UploadIdempotency idempotency;
    private final UploadReservationStore reservationStore;
    /** 上传内容去重（#6）：complete 路径只记账——下载字节算指纹、记表供未来上传复用（命中不重插）。 */
    private final ContentDedupService contentDedupService;
    /** 账号健康分（#8 信号源）。 */
    private final AccountHealthService accountHealthService;
    /** 三档风险机审评分器（#8）。 */
    private final RiskScorer riskScorer;
    /** 账号信用服务（#8 信号源）。 */
    private final AccountCreditService accountCreditService;
    /** 复审任务仓储（#8 落 RISK_TIERED 任务）。 */
    private final ReviewTaskRepository reviewTaskRepository;

    /**
     * 显式构造器（不用 {@code @RequiredArgsConstructor} / {@code @Slf4j}）：本机构建环境 Lombok
     * 对「重新编译的文件」处理不可靠，改为显式声明与日志字段（与 MediaUploadService 同口径）。
     */
    public MediaUploadFinalizer(MediaProperties properties, MediaStorageClient storageClient,
                                ImageProcessingChain processingChain, MediaJdbcRepository mediaRepository,
                                MediaEventPublisher eventPublisher, UploadIdempotency idempotency,
                                UploadReservationStore reservationStore, ContentDedupService contentDedupService,
                                AccountHealthService accountHealthService, RiskScorer riskScorer,
                                AccountCreditService accountCreditService, ReviewTaskRepository reviewTaskRepository) {
        this.properties = properties;
        this.storageClient = storageClient;
        this.processingChain = processingChain;
        this.mediaRepository = mediaRepository;
        this.eventPublisher = eventPublisher;
        this.idempotency = idempotency;
        this.reservationStore = reservationStore;
        this.contentDedupService = contentDedupService;
        this.accountHealthService = accountHealthService;
        this.riskScorer = riskScorer;
        this.accountCreditService = accountCreditService;
        this.reviewTaskRepository = reviewTaskRepository;
    }

    /**
     * 异步收尾：可选重编码 → 整帖批量落库（PENDING）→ 发一条帖级事件 → 回写幂等结果。
     *
     * <p>异常在本方法内收敛并记录，<b>不会</b>外泄到调用方（调用方早已返回 202 受理回执），
     * 因此这里是这类异步任务的最后一道 observable 边界，日志必须带 postId 便于排查。</p>
     */
    @Async("uploadFinalizeExecutor")
    public void finalizeAsync(UploadReservation reservation) {
        String postId = reservation.postId();
        List<String> mediaIds = reservation.slots().stream()
                .map(UploadReservation.Slot::mediaId)
                .toList();
        try {
            maybeReprocess(reservation);

            List<String> urls = mediaIds.stream()
                    .map(this::accessUrl)
                    .toList();
            mediaRepository.batchInsert(buildRows(reservation, urls));

            // #6 去重记账：complete 路径对象已由客户端直传到位，无法「跳过存储」，
            // 故仅下载字节算指纹、记表供未来上传复用（命中不重插、fail-open）。
            recordDedup(reservation);

            // #8 三档风险机审：内容安全（caption）已在 presign 通过，再依账号信号算风险分，
            // 达 MED/HIGH 送人工复审队列（RISK_TIERED）。fail-open 不阻断收尾。
            riskRoute(Long.parseLong(reservation.userId()), mediaIds.get(0), reservation.caption());

            // 整帖一条事件：与「整帖一审」配套，审核侧一次跑完，不做 N 张图的齐备性判断
            eventPublisher.publish(new MediaUploadedEvent(
                    postId, List.copyOf(mediaIds), List.copyOf(urls), reservation.userId(),
                    reservation.caption(), reservation.captionMark(),
                    reservation.requestId(), reservation.createdAt()));

            // 第 10 个参数 = 内容标签（#话题），与 MediaUploadService 同口径
            MediaItem post = new MediaItem(postId, mediaIds.get(0), urls.get(0), List.copyOf(urls), 0,
                    MediaStatus.PENDING, reservation.createdAt(),
                    reservation.caption(), reservation.captionMark(),
                    CaptionTagParser.parse(reservation.caption()), 0L, 0L, false);
            idempotency.store(reservation.userId(), reservation.requestId(), post);

            log.info("预签名上传收尾完成: postId={}, images={}, userId={}",
                    postId, mediaIds.size(), reservation.userId());
        } catch (RuntimeException e) {
            log.error("预签名上传收尾失败，开始补偿清理: postId={}, userId={}",
                    postId, reservation.userId(), e);
            cleanup(reservation.userId(), postId, mediaIds);
        } finally {
            // 无论成功还是已补偿清理，本批对象都已不再是「待落库」状态：
            // 成功者已有 DB 行、失败者已被删除，都必须从孤儿名单中释放，否则会被清理任务误删
            reservationStore.releasePending(mediaIds);
            reservationStore.delete(postId);
        }
    }

    /**
     * 处理链（可选）：下载 → 重编码 → 覆盖写回同一对象名。
     *
     * <p><b>与旧链路的差异</b>：网关收字节流时，处理发生在存储<b>之前</b>；
     * 客户端直传后字节已落对象存储，处理只能发生在存储<b>之后</b>（先下载再回写）。
     * 这正是抖音「先直传、再异步转码」的形状，代价是处理链开启时会多一次网关↔对象存储的往返
     * ——所以默认仍关闭（{@code processing-enabled=false}），保持零额外 IO。</p>
     */
    private void maybeReprocess(UploadReservation reservation) {
        if (!properties.isProcessingEnabled()) {
            return;
        }
        for (UploadReservation.Slot slot : reservation.slots()) {
            // 与上传主链路同一取舍：webp 无 ImageIO 编解码、gif 保留动图，跳过处理
            if (slot.format() == ImageFormat.WEBP || slot.format() == ImageFormat.GIF) {
                continue;
            }
            try {
                byte[] raw = storageClient.download(slot.mediaId());
                byte[] processed = processingChain.process(raw, slot.format().extension());
                if (processed != null) {
                    // 覆盖写回同名对象：mediaId 与 URL 均不变，已落库的引用无需更新
                    storageClient.overwrite(slot.mediaId(), processed, slot.format().contentType());
                }
            } catch (Exception e) {
                // 处理失败降级保留原图：绝不因重编码失败丢掉用户已传成功的内容
                log.warn("直传后处理失败，降级保留原图: mediaId={}, {}", slot.mediaId(), e.getMessage());
            }
        }
    }

    /** 组装整帖待落库行（同 user_id 落同片，batchInsert 合并为单批）。 */
    private List<MediaJdbcRepository.MediaRowSpec> buildRows(UploadReservation r, List<String> urls) {
        long uid = Long.parseLong(r.userId());
        List<MediaJdbcRepository.MediaRowSpec> rows = new ArrayList<>(r.slots().size());
        for (int i = 0; i < r.slots().size(); i++) {
            UploadReservation.Slot slot = r.slots().get(i);
            rows.add(new MediaJdbcRepository.MediaRowSpec(
                    r.postId(), slot.mediaId(), uid, urls.get(i), MediaStatus.PENDING,
                    r.caption(), r.captionMark(), slot.seq(), r.createdAt(), slot.duration()));
        }
        return rows;
    }

    /**
     * #6 去重记账（complete 路径，只记账）：逐张下载对象字节算 SHA-256，记指纹表供未来上传复用。
     *
     * <p><b>为什么是「只记账」而非「跳过存储」</b>：直传路径对象已由客户端写入对象存储，无法回退；
     * 此处只把指纹落到 media_dedup，使后续走网关收字节路径（{@code MediaUploadService#storeOneWithDedup}）
     * 的相同内容能命中复用、省下新对象。命中（指纹已存在）则 INSERT IGNORE 幂等忽略。</p>
     *
     * <p><b>失败方向</b>：整条链 fail-open——下载/算哈希/记表任一异常只告警，绝不影响已落库的帖子。</p>
     */
    private void recordDedup(UploadReservation reservation) {
        if (!contentDedupService.isEnabled()) {
            return;
        }
        long uid = Long.parseLong(reservation.userId());
        for (UploadReservation.Slot slot : reservation.slots()) {
            try {
                byte[] bytes = storageClient.download(slot.mediaId());
                String hash = contentDedupService.sha256(bytes);
                contentDedupService.record(uid, hash, slot.mediaId(), accessUrl(slot.mediaId()));
            } catch (IOException e) {
                log.warn("去重记账下载/算哈希失败（跳过该张）: mediaId={}, {}", slot.mediaId(), e.getMessage());
            } catch (Exception e) {
                log.warn("去重记账异常（跳过该张）: mediaId={}, {}", slot.mediaId(), e.getMessage());
            }
        }
    }

    /** 生成客户端可访问的媒体 URL：minio 私有桶走预签名 GET，本地/占位走公网基址拼接。 */
    private String accessUrl(String mediaId) {
        if ("minio".equals(properties.getStorage())) {
            try {
                return storageClient.presignedGetUrl(mediaId,
                        Duration.ofSeconds(properties.getMinio().getUrlExpirySeconds()));
            } catch (Exception e) {
                log.warn("预签名读取 URL 生成失败（回退公网基址）: mediaId={}, {}", mediaId, e.getMessage());
            }
        }
        return properties.getPublicUrlBase() + mediaId;
    }

    /**
     * #8 三档风险机审路由（complete 路径，口径与 {@code MediaUploadService#riskRoute} 一致）。
     *
     * <p>异步线程无 JWT 上下文，账号身份从 {@link UploadReservation} 取（presign 阶段已完成越权校验）。
     * fail-open：读信号/算分/建任务任一异常默认按 MED 送审，绝不阻断收尾。</p>
     */
    private void riskRoute(long userId, String representativeMediaId, String caption) {
        if (!properties.getReview().isRiskEnabled()) {
            return;
        }
        RiskScorer.RiskLevel level;
        try {
            AccountHealth health = accountHealthService.getHealth(userId);
            RiskScorer.RiskResult result = riskScorer.score(userId, caption, health, null);
            level = result.level();
        } catch (Exception e) {
            log.warn("上传风险评分失败（默认按 MED 送审）: userId={}, mediaId={}, {}",
                    userId, representativeMediaId, e.getMessage());
            level = RiskScorer.RiskLevel.MED;
        }
        if (level != RiskScorer.RiskLevel.LOW) {
            int tierCode = level == RiskScorer.RiskLevel.HIGH ? 2 : 1;
            try {
                reviewTaskRepository.insert(representativeMediaId, userId, ReviewTask.TYPE_RISK_TIERED, tierCode);
                log.info("上传风险机审送审: userId={}, mediaId={}, level={}", userId, representativeMediaId, level);
            } catch (Exception e) {
                log.warn("上传风险机审建任务失败（忽略，不影响收尾）: userId={}, mediaId={}, {}",
                        userId, representativeMediaId, e.getMessage());
            }
        }
    }

    /** 收尾失败的补偿清理：删对象（fail-open）+ 物理删除本批已落库的残行。 */
    private void cleanup(String userId, String postId, List<String> mediaIds) {
        for (String mediaId : mediaIds) {
            try {
                storageClient.delete(mediaId);
            } catch (Exception e) {
                log.warn("补偿清理：删除对象失败（可能残留对象）: mediaId={}, {}", mediaId, e.getMessage());
            }
        }
        try {
            mediaRepository.hardDeleteByPost(Long.parseLong(userId), postId);
        } catch (Exception e) {
            log.error("补偿清理：物理删除残行失败，可能残留半成品帖: postId={}, userId={}", postId, userId, e);
        }
    }
}
