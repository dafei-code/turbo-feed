package com.turbofeed.gateway.service;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.config.SentinelRateLimitConfig;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.security.UserContext;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.event.MediaEventPublisher;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import com.turbofeed.gateway.service.idempotency.UploadIdempotency;
import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.service.presign.PresignRequest;
import com.turbofeed.gateway.service.presign.PresignResponse;
import com.turbofeed.gateway.service.presign.UploadAccepted;
import com.turbofeed.gateway.service.presign.UploadReservation;
import com.turbofeed.gateway.service.presign.UploadReservationStore;
import com.turbofeed.gateway.service.processing.ImageProcessingChain;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.service.validation.UploadValidation;
import com.turbofeed.gateway.service.validation.UploadValidationChain;
import com.turbofeed.gateway.storage.MediaStorageClient;
import com.turbofeed.gateway.util.CaptionMarkParser;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 内容图片上传服务：责任链校验 -&gt; 用户维度限流 -&gt; 幂等去重 -&gt; 逐张「处理 -&gt; 存储」-&gt; 整帖批量落库 -&gt; 整帖发事件。
 *
 * <p>校验整体外移至 {@code service/validation} 责任链
 * （{@link UploadValidationChain}）：批量数 / 空文件 / 大小 / Magic Number /
 * 用户级并发护栏——新增校验规则只需增加链环节，本类零改动。校验产出的真实格式
 * 经 {@link UploadValidation#format(int)} 写回，存储阶段不二次读文件头。</p>
 *
 * <p>校验任一失败抛 {@link BizException}（UPLOAD_INVALID / UPLOAD_IN_PROGRESS），
 * 由全局异常处理器统一响应。{@code storeOne} 保持流式路径：默认不
 * {@code getBytes()} 全量加载，仅处理链启用时全量读取交给 ImageIO。</p>
 *
 * <h3>一次上传 = 一个帖子（抖音式图文）</h3>
 * <p>同一次请求的 N 张图（{@code turbofeed.media.max-batch-count} 上限 9）共享一个
 * {@code postId}，{@code seq} 为 0 起的帖内序号（决定前端轮播顺序）。因此：</p>
 * <ul>
 *   <li>返回值是<b>帖子视图</b>（{@link MediaItem}：postId + images + status），不再是 URL 数组
 *   —— 只回 URL 会丢掉帖子身份，前端无法渲染轮播；</li>
 *   <li>发的是<b>一条帖级事件</b>（{@link MediaUploadedEvent}），不是 N 条文件级事件 ——
 *   否则审核侧要自己做「9 张图各自事件的齐备性判断」，既引入竞态又需要额外计数状态；</li>
 *   <li>与「整帖一审」配套：事件到达即代表整帖已全部落库，机审一次、状态一次翻转、公域一次投递。</li>
 * </ul>
 *
 * <p><b>中途失败的补偿清理</b>：若第 k 张存储/落库失败，前 k-1 张的 DB 行与对象存储对象必须删掉。
 * 否则会留下「没有 seq=0 代表行 / 张数不齐」的半成品帖——它既不出现在「我的内容」
 * （聚合查询按代表行取帖），用户也无法删除（删不到不存在的帖），是最难排查的一类脏数据。</p>
 *
 * <p>UGC 口径下上传成功仅代表受理，进入待审核态（PENDING），审核通过后才对前端可见。</p>
 */
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    /** 帖子 ID 前缀：形如 {@code post/{userId}/{uuid}}，与 media_id 同构便于日志辨识归属。 */
    private static final String POST_ID_PREFIX = "post/";

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final MediaEventPublisher eventPublisher;
    private final ImageProcessingChain processingChain;
    private final UploadValidationChain validationChain;
    private final UploadRateLimiter rateLimiter;
    private final UploadIdempotency idempotency;
    private final MediaJdbcRepository mediaRepository;
    private final FeedTimelinePublisher feedTimelinePublisher;
    private final StringRedisTemplate redisTemplate;
    private final CaptionMarkParser captionMarkParser;
    private final ContentSecurityService contentSecurityService;
    private final UploadReservationStore reservationStore;
    private final MediaUploadFinalizer uploadFinalizer;

    /** 单条状态缓存前缀（与 MediaReviewService 一致，删除时精确失效） */
    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

    /** Magic Number 探测字节数：需覆盖 WEBP（标识位于偏移 8、长 4 字节），故取 12。 */
    private static final int HEADER_PROBE_BYTES = 12;

    /** 单张图片的存储结果（mediaId 用于落库与后续删除，url 用于拼装帖子视图）。 */
    private record StoredOne(String mediaId, String url) {
    }

    /**
     * 批量上传图片（一次请求 = 一个帖子），返回帖子视图。
     *
     * <p>归属用户不经过方法参数：从请求线程上下文
     * {@link UserContextHolder#requireUserId()} 获取（JWT 验签写入，客户端无法指定他人；
     * 未携带有效令牌即 UNAUTHORIZED）。单测可通过 {@code UserContextHolder.set/clear}
     * 构造身份。</p>
     *
     * <p><b>Sentinel 机器维度限流（注解驱动）</b>：{@link SentinelResource} 由
     * SentinelResourceAspect（SCA starter 自动装配，{@code spring.cloud.sentinel.annotation.enabled}
     * 默认 true）代理本方法——规则命中即回调 {@link #uploadBlocked} 转
     * {@link BizException}(RATE_LIMITED)，entry/exit 由切面成对管理（业务抛异常时也保证
     * exit，无泄漏）。资源名复用 {@link SentinelRateLimitConfig#UPLOAD_RESOURCE} 单一事实源，
     * {@code entryType = IN}（Web 入口流量；注解默认为 OUT，须显式声明）。</p>
     *
     * <p><b>用户维度限流（Redis）</b>：{@link UploadRateLimiter#tryAcquire} 在 Sentinel 放行后、
     * 校验链之前执行，跨实例统一计数（per-user + 时间窗，见 {@code MediaProperties.RateLimit}）。
     * 机器维度保进程、用户维度防单用户刷接口，两层互补。</p>
     *
     * <p><b>幂等去重</b>：{@link UploadIdempotency#check} 在限流之后、校验之前执行；
     * 同一 {@code requestId}（结合 userId）5s 内重复提交直接返回首次受理结果（帖子视图），
     * 避免重复落存储 / 送审 / 占限流配额。requestId 缺省则跳过幂等。</p>
     *
     * <p><b>校验链</b>：{@link UploadValidationChain#validate(String, MultipartFile[])}
     * 在 {@code try} 块内执行，{@code finally} 统一触发完成回调——无论成功、校验失败
     * （{@code UPLOAD_INVALID / UPLOAD_IN_PROGRESS}）还是业务异常（{@code storeOne} 抛错），
     * 用户级上传占位（{@code rl:upload:inflight:{userId}}）都被立即释放；TTL 仅兜底进程崩溃
     * / 释放失败场景（{@code inflight-ttl-seconds}）。若 {@code validate()} 置于 try 之外，
     * 校验失败时占位 key 会滞留 TTL 期内，同用户重试会被 42903 误拒——见 changelog 0009。</p>
     *
     * @param files     multipart 字段 files 的上传文件数组（1..9 张，上限见 {@code turbofeed.media.max-batch-count}）
     * @param caption   抖音式描述/标题（本批图片共享，即帖子级文案；可空）
     * @param requestId 客户端幂等键（可选，X-Request-Id 请求头；5s 内重复提交去重返回首次结果）
     * @return 帖子视图（含 postId 与按 seq 排序的 images），审核通过后对前端生效
     */
    @SentinelResource(value = SentinelRateLimitConfig.UPLOAD_RESOURCE,
            entryType = EntryType.IN, blockHandler = "uploadBlocked")
    public MediaItem upload(MultipartFile[] files, String caption, String requestId) {
        String userId = UserContextHolder.requireUserId();

        // 用户维度限流（机器维度已由 Sentinel 注解外层放行）
        if (!rateLimiter.tryAcquire(userId)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
        }

        // 幂等去重：同一 requestId（结合 userId）窗口内重复提交返回首次结果
        UploadIdempotency.IdempotencyOutcome outcome = idempotency.check(userId, requestId);
        if (outcome.isDuplicate()) {
            MediaItem first = outcome.post();
            log.info("上传幂等命中，返回首次结果: userId={}, requestId={}, postId={}, images={}",
                    userId, requestId, first == null ? null : first.postId(),
                    first == null || first.images() == null ? 0 : first.images().size());
            return first;
        }
        if (outcome.isInProgress()) {
            throw new BizException(ErrorCode.UPLOAD_IN_PROGRESS, "请求处理中，请勿重复提交");
        }

        // 描述/标题：抖音式文案（@用户 / #话题 / [image:idx:filename]）。
        // 长度上限 + 敏感词统一走内容安全入口（场景 CAPTION），命中即 fail-closed；
        // 解析为 caption_mark 落库。一次上传一个 caption 共享给整帖。
        String rawCaption = caption == null ? "" : caption;
        long uid = Long.parseLong(userId);
        contentSecurityService.requireClean(ContentScene.CAPTION, rawCaption, uid);
        CaptionMarkParser.ParseResult captionMark = captionMarkParser.parse(rawCaption);
        String postId = POST_ID_PREFIX + userId + "/" + UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now();

        UploadValidation context = null;
        List<String> mediaIds = new ArrayList<>(files.length);
        List<String> urls = new ArrayList<>(files.length);
        List<MediaJdbcRepository.MediaRowSpec> rows = new ArrayList<>(files.length);
        String markJson = captionMark.markJson();
        try {
            context = validationChain.validate(userId, files);
            for (int seq = 0; seq < files.length; seq++) {
                StoredOne stored = storeOne(userId, files[seq], context.format(seq));
                mediaIds.add(stored.mediaId());
                urls.add(stored.url());
                rows.add(new MediaJdbcRepository.MediaRowSpec(
                        postId, stored.mediaId(), Long.parseLong(userId), stored.url(),
                        MediaStatus.PENDING, rawCaption, markJson, seq, createdAt));
            }
            // 整帖落库：N 张图一次 batch（同 user_id 落同片，rewriteBatchedStatements 合并为单批）
            mediaRepository.batchInsert(rows);
        } catch (RuntimeException e) {
            // 中途失败：清掉本批已落库的残行与已上传的对象，绝不留半成品帖（见类注释）
            cleanupPartialPost(uid, postId, mediaIds);
            throw e;
        } finally {
            // 覆盖 validate() 抛 UPLOAD_INVALID/UPLOAD_IN_PROGRESS 与 storeOne 异常：
            // 占位 key 必须释放，否则 TTL 兜底期内同用户重试会被 42903 误拒。
            if (context != null) {
                context.runCompletionCallbacks();
            }
        }

        // 整帖已全部落库 → 发**一条帖级事件**（审核按帖一次跑：机审一次、状态一次翻转、公域一次投递）
        eventPublisher.publish(new MediaUploadedEvent(
                postId, List.copyOf(mediaIds), List.copyOf(urls), userId,
                rawCaption, captionMark.markJson(), requestId, createdAt));

        MediaItem post = new MediaItem(postId, mediaIds.get(0), urls.get(0), List.copyOf(urls), 0,
                MediaStatus.PENDING, createdAt, rawCaption, captionMark.markJson());
        log.info("帖子上传受理: userId={}, postId={}, images={}, requestId={}, captionLen={}",
                userId, postId, urls.size(), requestId, rawCaption.length());
        idempotency.store(userId, requestId, post);
        return post;
    }

    /**
     * Sentinel 限流回调（blockHandler）：规则命中时替代 {@link #upload} 执行。
     *
     * <p>签名契约（SentinelResourceAspect 约束）：与原方法同参 + 末尾 {@link BlockException}、
     * 同返回类型、同类 public 实例方法。不吞 BlockException 而是转成统一业务异常
     * {@link BizException}(RATE_LIMITED)，由全局异常处理器输出标准错误响应。</p>
     */
    public MediaItem uploadBlocked(MultipartFile[] files, String caption, String requestId, BlockException e) {
        UserContext context = UserContextHolder.get();
        String userId = context != null ? context.userId() : "anonymous";
        log.warn("上传限流触发: userId={}, requestId={}, rule={}", userId, requestId, e.getClass().getSimpleName());
        throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
    }

    // ==================== 预签名直传：签发凭证 / 通知完成 ====================

    /**
     * 申请预签名上传凭证（抖音式客户端直传）：网关只签发凭证，字节由客户端直传对象存储。
     *
     * <p><b>链路</b>：本方法（签发）→ 客户端 PUT 直传 → {@link #complete}（通知完成）。
     * 与 {@link #upload} 产出同一个「帖子」，区别只在字节入口：<code>upload</code> 由网关收字节流
     * （保留给 {@code storage=local} 与旧客户端），本方法把最重的网络 IO 从计算集群剥离，
     * 是高并发下的推荐入口。</p>
     *
     * <p><b>校验分两段</b>：此处<b>没有字节可读</b>，只校验声明值（数量 / 声明大小 /
     * contentType 白名单）；真实格式与真实大小在 {@link #complete} 阶段由服务端读对象复核。
     * 伪造 contentType 最多影响对象名后缀，过不了文件头复检。</p>
     *
     * @param request   文件元数据 + 整帖文案
     * @param requestId 客户端幂等键（可选，X-Request-Id；窗口内重复申请复用同一预约）
     */
    @SentinelResource(value = SentinelRateLimitConfig.UPLOAD_RESOURCE,
            entryType = EntryType.IN, blockHandler = "presignBlocked")
    public PresignResponse presign(PresignRequest request, String requestId) {
        String userId = UserContextHolder.requireUserId();

        if (!rateLimiter.tryAcquire(userId)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
        }
        // 预签名是 S3 协议能力：本地磁盘 / 占位实现无此能力，给明确提示而不是 500
        if (!"minio".equalsIgnoreCase(properties.getStorage())) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "预签名直传仅在 turbofeed.media.storage=minio 时可用");
        }

        // 幂等：同一 requestId 复用同一预约 → 同一批对象名，避免重试把孤儿对象翻倍
        Optional<String> existed = reservationStore.findPostIdByRequest(userId, requestId);
        if (existed.isPresent()) {
            Optional<UploadReservation> resv = reservationStore.load(existed.get());
            if (resv.isPresent()) {
                log.info("预签幂等命中，复用原预约: userId={}, requestId={}, postId={}",
                        userId, requestId, resv.get().postId());
                return toPresignResponse(resv.get());
            }
        }

        List<PresignRequest.FileMeta> files = request != null && request.files() != null
                ? request.files() : List.of();
        if (files.isEmpty() || files.size() > properties.getMaxBatchCount()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "上传数量不合法（1.." + properties.getMaxBatchCount() + "）");
        }

        String rawCaption = request != null && request.caption() != null ? request.caption() : "";
        contentSecurityService.requireClean(ContentScene.CAPTION, rawCaption, Long.parseLong(userId));
        CaptionMarkParser.ParseResult captionMark = captionMarkParser.parse(rawCaption);

        long maxBytes = properties.getMaxFileSize().toBytes();
        List<UploadReservation.Slot> slots = new ArrayList<>(files.size());
        for (int seq = 0; seq < files.size(); seq++) {
            PresignRequest.FileMeta meta = files.get(seq);
            ImageFormat format = ImageFormat.fromContentType(meta.contentType());
            if (format == null) {
                throw new BizException(ErrorCode.UPLOAD_INVALID, "不支持的图片类型: " + meta.contentType());
            }
            if (meta.size() <= 0 || meta.size() > maxBytes) {
                throw new BizException(ErrorCode.UPLOAD_INVALID, "文件大小超限: " + meta.size());
            }
            slots.add(new UploadReservation.Slot(seq,
                    storageClient.generateMediaId(userId, format), format, meta.size()));
        }

        String postId = POST_ID_PREFIX + userId + "/" + UUID.randomUUID().toString().replace("-", "");
        UploadReservation reservation = new UploadReservation(postId, userId, List.copyOf(slots),
                rawCaption, captionMark.markJson(), requestId, Instant.now(),
                UploadReservation.Status.RESERVED);
        reservationStore.save(reservation);
        reservationStore.saveRequestIndex(userId, requestId, postId);

        PresignResponse response = toPresignResponse(reservation);
        log.info("预签名凭证已签发: userId={}, postId={}, images={}, requestId={}",
                userId, postId, slots.size(), requestId);
        return response;
    }

    /** Sentinel 限流回调（blockHandler）：签名契约同 {@link #uploadBlocked}。 */
    public PresignResponse presignBlocked(PresignRequest request, String requestId, BlockException e) {
        UserContext context = UserContextHolder.get();
        String userId = context != null ? context.userId() : "anonymous";
        log.warn("预签限流触发: userId={}, requestId={}, rule={}", userId, requestId, e.getClass().getSimpleName());
        throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
    }

    /** 按预约签发（重签）上传 URL：同一批对象名可重复签发，用于幂等复用与凭证过期重试。 */
    private PresignResponse toPresignResponse(UploadReservation reservation) {
        int expiry = properties.getPresign().getExpirySeconds();
        Duration ttl = Duration.ofSeconds(expiry);
        List<PresignResponse.PresignItem> items = new ArrayList<>(reservation.slots().size());
        for (UploadReservation.Slot slot : reservation.slots()) {
            items.add(new PresignResponse.PresignItem(slot.seq(), slot.mediaId(),
                    storageClient.presignedPutUrl(slot.mediaId(), slot.format().contentType(), ttl)));
        }
        return new PresignResponse(reservation.postId(), List.copyOf(items), expiry);
    }

    /**
     * 通知「客户端已直传完成」：复核对象 → 异步收尾 → 立即返回受理回执。
     *
     * <p><b>为什么还要复核</b>：凭证只保证「能传到指定对象名」，不保证内容合规。
     * 若跳过复核直接落库，客户端就能用图片凭证上传任意字节（含伪装扩展名的可执行内容），
     * 而公域展示依赖的正是库里的 URL。故此处用 stat 复核大小、用文件头复核真实格式——
     * 与 {@code upload} 里 {@code FileConstraintValidator} 的口径保持一致。</p>
     *
     * <p><b>为什么返回受理而非最终结果</b>：落库与送审已异步化（{@code MediaUploadFinalizer}），
     * 本接口只保证「校验通过 + 收尾已提交」。客户端凭回执里的 mediaId 轮询
     * {@code GET /api/media/status} 获取最终状态（收尾未完成时该接口按既有语义返回 PENDING）。</p>
     *
     * @param postId    帖子 ID（取自 {@link PresignResponse#postId()}）
     * @param requestId 客户端幂等键（可选）
     */
    public UploadAccepted complete(String postId, String requestId) {
        String userId = UserContextHolder.requireUserId();
        UploadReservation reservation = reservationStore.load(postId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "上传预约不存在或已过期，请重新申请凭证"));
        if (!reservation.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权完成他人的上传");
        }
        String representative = reservation.slots().isEmpty()
                ? null : reservation.slots().get(0).mediaId();

        // 原子领取收尾权：重复 complete 只有一次能落库 / 发事件，其余按幂等返回
        if (!reservationStore.claim(postId)) {
            log.info("重复完成通知，按幂等返回: postId={}, userId={}", postId, userId);
            return new UploadAccepted(postId, representative, MediaStatus.PENDING.name());
        }

        long maxBytes = properties.getMaxFileSize().toBytes();
        for (UploadReservation.Slot slot : reservation.slots()) {
            long size = storageClient.sizeOf(slot.mediaId());
            if (size <= 0 || size > maxBytes) {
                discardUploadedObjects(reservation);
                throw new BizException(ErrorCode.UPLOAD_INVALID, "上传对象缺失或大小超限: seq=" + slot.seq());
            }
            ImageFormat actual = ImageFormat.detect(
                    storageClient.probeHeader(slot.mediaId(), HEADER_PROBE_BYTES));
            if (actual != slot.format()) {
                discardUploadedObjects(reservation);
                throw new BizException(ErrorCode.UPLOAD_INVALID, "文件内容与声明类型不符: seq=" + slot.seq());
            }
        }

        uploadFinalizer.finalizeAsync(reservation);
        return new UploadAccepted(postId, representative, MediaStatus.PENDING.name());
    }

    /** 复核失败的清理：此时尚未落库，只需删掉已直传的对象并释放预约。 */
    private void discardUploadedObjects(UploadReservation reservation) {
        for (UploadReservation.Slot slot : reservation.slots()) {
            try {
                storageClient.delete(slot.mediaId());
            } catch (Exception e) {
                log.warn("复核失败清理对象异常（残留对象依赖清理任务）: mediaId={}, {}",
                        slot.mediaId(), e.getMessage());
            }
        }
        // 复核失败时对象已删除，须同步从孤儿名单释放（否则清理任务会重复删一次）
        reservationStore.releasePending(reservation.slots().stream()
                .map(UploadReservation.Slot::mediaId).toList());
        reservationStore.delete(reservation.postId());
    }

    /**
     * 单张图片：可选处理 -&gt; 存储，返回 mediaId 与 URL（落库已移出，由 {@link #upload} 收尾批量写入 PENDING）。
     *
     * <p>{@code postId} 与 {@code seq} 仅用于日志/回调标识；真正落库在 {@code upload} 主流程
     * 统一 {@link MediaJdbcRepository#batchInsert} 完成，把逐张串行写合并为一次批量，压平写放大。</p>
     */
    private StoredOne storeOne(String userId, MultipartFile file, ImageFormat format) {
        byte[] processed = maybeProcess(file, format);
        try (InputStream content = processed != null
                ? new ByteArrayInputStream(processed)
                : file.getInputStream()) {
            long size = processed != null ? processed.length : file.getSize();
            MediaStorageClient.StoredMedia stored =
                    storageClient.store(userId, format, content, size);
            return new StoredOne(stored.mediaId(), stored.url());
        } catch (IOException e) {
            log.error("读取上传内容失败: userId={}, size={}", userId, file.getSize(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传内容失败");
        }
    }

    /**
     * 上传中途失败的补偿清理：删对象存储对象（fail-open）+ 物理删除本批已落库的残行。
     *
     * <p>残行从未对用户可见，硬删不会损失审计价值；留下的半成品帖反而是「不可见又删不掉」的脏数据。
     * 两步都各自 try 住：清理本身失败也不能掩盖原始异常（{@code throw e} 在调用方）。</p>
     */
    private void cleanupPartialPost(long userId, String postId, List<String> storedMediaIds) {
        for (String mediaId : storedMediaIds) {
            try {
                storageClient.delete(mediaId);
            } catch (Exception e) {
                log.warn("补偿清理：删除对象存储失败（可能残留对象）: mediaId={}, {}", mediaId, e.getMessage());
            }
        }
        try {
            mediaRepository.hardDeleteByPost(userId, postId);
        } catch (Exception e) {
            log.error("补偿清理：物理删除残行失败，可能残留半成品帖: postId={}, userId={}", postId, userId, e);
        }
    }

    /**
     * 处理链（Decorator）：启用且格式可处理时全量读取并重编码；失败降级返回 null（走原图），
     * 绝不因处理失败阻断上传。webp（无 ImageIO 编解码）与 gif（保留动图）跳过处理。
     */
    private byte[] maybeProcess(MultipartFile file, ImageFormat format) {
        if (!properties.isProcessingEnabled()) {
            return null;
        }
        if (format == ImageFormat.WEBP || format == ImageFormat.GIF) {
            log.debug("处理链跳过: format={}（webp 无 ImageIO 编解码，gif 保留动图）", format);
            return null;
        }
        try {
            byte[] raw = file.getBytes();  // 仅处理链启用时全量加载；默认关闭保持流式路径
            return processingChain.process(raw, format.extension());
        } catch (IOException e) {
            log.warn("图片处理失败，降级使用原图: format={}", format, e);
            return null;
        }
    }

    /**
     * 删除用户自己上传的<b>帖子</b>：<b>物理删除（对象存储，逐张）+ 逻辑删除（MySQL，整帖）</b> 两段式。
     *
     * <p><b>为什么是整帖</b>：一帖多图下按单张删除会留下「孤儿图」——其余图仍是 APPROVED，
     * 公域时间线里那一条帖子仍可见。整帖一起删才是自洽语义，也与「整帖一审」对称。</p>
     *
     * <p><b>为什么两段</b>：物理对象一旦删除不可恢复，故先删 MinIO（或本地磁盘）对象，
     * 失败仅告警不阻断（对象可能已不存在）；再在 MySQL 把状态置 {@link MediaStatus#DELETED}
     * （逻辑删除，保留审计痕迹）。随后清理公域时间线与整帖状态缓存，
     * 保证删除后立即从「我的内容」与公域发现流消失。</p>
     *
     * <p><b>时间线的键</b>：必须与投递时一致（有 postId 用 postId，历史单图数据回退 mediaId），
     * 否则引擎侧反查索引对不上，下架失效、内容继续可见。</p>
     *
     * <p><b>越权防护</b>：{@code userId} 来自 JWT（服务端签发，非客户端可控），与 mediaId 一并
     * 下传仓库的 {@code (media_id, user_id)} 条件——即使传入他人 mediaId，因分片键不匹配也删不到
     * 别人的行。</p>
     *
     * @param mediaId 内容唯一标识（帖代表行，形如 media/{userId}/{uuid}.{ext}）
     * @param userId  归属用户（来自 JWT）
     */
    public void delete(String mediaId, String userId) {
        long uid = Long.parseLong(userId);
        String postId = mediaRepository.findPostId(mediaId, uid);
        boolean legacySingle = postId == null || postId.isBlank();

        // 先取全帖行：既用于逐个删对象存储，也用于清整帖状态缓存
        List<MediaItem> rows;
        if (legacySingle) {
            MediaItem single = mediaRepository.findMedia(mediaId, uid);
            rows = single == null ? List.of() : List.of(single);
        } else {
            rows = mediaRepository.listPostImages(uid, postId);
        }

        // 1) 物理删除：逐张删对象存储（fail-open，对象不存在也继续）
        for (MediaItem row : rows) {
            try {
                storageClient.delete(row.mediaId());
            } catch (Exception e) {
                log.warn("物理删除异常（继续逻辑删除）: mediaId={}, {}", row.mediaId(), e.getMessage());
            }
        }
        // 2) 逻辑删除：MySQL 整帖标记 DELETED（带 user_id 分片键，仅删本人内容）
        mediaRepository.delete(mediaId, uid);
        // 3) 清理公域时间线：键与投递时一致（有 postId 用 postId，历史数据回退 mediaId）。
        //    fail-open：引擎不可用时仅告警，不阻断删除——内容已物理+逻辑删除，
        //    可见性残留由引擎侧兜底清理与推荐流缓存 TTL 收敛
        feedTimelinePublisher.remove(legacySingle ? mediaId : postId);
        // 4) 失效整帖状态缓存（旁路缓存 fail-open）：状态是整帖的，只清代表行会让其余图在 TTL 内返回旧状态
        try {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(STATUS_KEY_PREFIX + mediaId);
            for (MediaItem row : rows) {
                keys.add(STATUS_KEY_PREFIX + row.mediaId());
            }
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
        log.info("帖子已删除（物理+逻辑）: mediaId={}, postId={}, images={}, userId={}",
                mediaId, legacySingle ? null : postId, rows.size(), userId);
    }

    /**
     * 更新媒体描述/标题（用户编辑已上传内容的文案）。
     *
     * <p>流程：长度上限 + 敏感词统一走内容安全入口（场景 CAPTION，命中即 fail-closed）
     * → 解析为 caption_mark → 落库。
     * 带 user_id 分片键，仅本人内容可改（与 delete 同源防护）。该方法不重置
     * 审核状态——描述修改不影响内容流转（合规要求：涉政违规仅下架而非拒重传）。</p>
     *
     * <p><b>描述是帖子级属性</b>：仓库层会把新文案写到帖内全部行，避免同帖各图文案分叉。</p>
     *
     * @param mediaId 内容唯一标识（贴代表行，含斜杠）
     * @param userId  归属用户（来自 JWT，非客户端可控）
     * @param caption 新描述/标题（可为 null / 空表示清空）
     */
    public void updateCaption(String mediaId, String userId, String caption) {
        String raw = caption == null ? "" : caption;
        contentSecurityService.requireClean(ContentScene.CAPTION, raw, Long.parseLong(userId));
        CaptionMarkParser.ParseResult pr = captionMarkParser.parse(raw);
        mediaRepository.updateCaption(mediaId, Long.parseLong(userId), raw, pr.markJson());
        log.info("媒体描述已更新（整帖）: mediaId={}, userId={}, captionLen={}", mediaId, userId, raw.length());
    }
}
