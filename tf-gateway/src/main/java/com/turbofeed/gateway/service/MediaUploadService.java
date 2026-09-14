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
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 内容图片上传服务：责任链校验 -&gt; 用户维度限流 -&gt; 幂等去重 -&gt; 逐张「处理 -&gt; 存储 -&gt; 落库」-&gt; 整帖发事件。
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
    private final SensitiveWordService sensitiveWordService;

    /** 单条状态缓存前缀（与 MediaReviewService 一致，删除时精确失效） */
    private static final String STATUS_KEY_PREFIX = "tf:media:status:";

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

        // 描述/标题：抖音式文案（@用户 / #话题 / [image:idx:filename]），
        // 同步敏感词 fail-closed；解析为 caption_mark 落库。一次上传一个 caption 共享给整帖。
        String rawCaption = caption == null ? "" : caption;
        sensitiveWordService.requireClean(rawCaption);
        CaptionMarkParser.ParseResult captionMark = captionMarkParser.parse(rawCaption);

        long uid = Long.parseLong(userId);
        String postId = POST_ID_PREFIX + userId + "/" + UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now();

        UploadValidation context = null;
        List<String> mediaIds = new ArrayList<>(files.length);
        List<String> urls = new ArrayList<>(files.length);
        try {
            context = validationChain.validate(userId, files);
            for (int seq = 0; seq < files.length; seq++) {
                StoredOne stored = storeOne(postId, seq, userId, files[seq], context.format(seq),
                        rawCaption, captionMark.markJson(), createdAt);
                mediaIds.add(stored.mediaId());
                urls.add(stored.url());
            }
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

    /**
     * 单张图片：可选处理 -&gt; 存储 -&gt; 落库 PENDING，返回 mediaId 与 URL。
     *
     * <p>带上 {@code postId} 与 {@code seq} 落库，使该行成为帖子的一员；{@code seq} 决定前端
     * 轮播顺序，因此必须用调用方传入的下标、不要在这里重新排序。</p>
     */
    private StoredOne storeOne(String postId, int seq, String userId, MultipartFile file,
                               ImageFormat format, String caption, String captionMark, Instant createdAt) {
        byte[] processed = maybeProcess(file, format);
        try (InputStream content = processed != null
                ? new ByteArrayInputStream(processed)
                : file.getInputStream()) {
            long size = processed != null ? processed.length : file.getSize();
            MediaStorageClient.StoredMedia stored =
                    storageClient.store(userId, format, content, size);
            // 先同步落 PENDING：消除「存储成功但审核事件消费前进程崩溃」导致的 MinIO 孤儿对象。
            // insert 以 media_id 主键幂等（ON DUPLICATE KEY UPDATE），与 handleUploaded 兜底插互不冲突；
            // 即便事件丢失，media 表已留 PENDING 记录，可经巡检对账补审 / 清理。
            mediaRepository.insert(postId, stored.mediaId(), Long.parseLong(userId), stored.url(),
                    MediaStatus.PENDING, caption, captionMark, seq, createdAt);
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
     * <p>流程：原始文本过敏感词 fail-closed → 解析为 caption_mark → 落库。
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
        sensitiveWordService.requireClean(raw);
        CaptionMarkParser.ParseResult pr = captionMarkParser.parse(raw);
        mediaRepository.updateCaption(mediaId, Long.parseLong(userId), raw, pr.markJson());
        log.info("媒体描述已更新（整帖）: mediaId={}, userId={}, captionLen={}", mediaId, userId, raw.length());
    }
}
