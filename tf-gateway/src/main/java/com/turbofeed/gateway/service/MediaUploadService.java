package com.turbofeed.gateway.service;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.config.SentinelRateLimitConfig;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.UserContext;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.event.MediaEventPublisher;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.idempotency.UploadIdempotency;
import com.turbofeed.gateway.service.processing.ImageProcessingChain;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.service.validation.UploadValidation;
import com.turbofeed.gateway.service.validation.UploadValidationChain;
import com.turbofeed.gateway.storage.MediaStorageClient;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容图片上传服务：责任链校验 -&gt; 用户维度限流 -&gt; 幂等去重 -&gt; 逐文件「处理 -&gt; 存储 -&gt; 发事件」。
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
 * <p>UGC 口径下上传成功仅代表受理，进入待审核态（PENDING），审核通过后才对前端可见。</p>
 */
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final MediaEventPublisher eventPublisher;
    private final ImageProcessingChain processingChain;
    private final UploadValidationChain validationChain;
    private final UploadRateLimiter rateLimiter;
    private final UploadIdempotency idempotency;

    /**
     * 批量上传图片，返回可访问 URL 列表。
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
     * 同一 {@code requestId}（结合 userId）5s 内重复提交直接返回首次受理结果，避免重复落存储 /
     * 送审 / 占限流配额。requestId 缺省则跳过幂等。</p>
     *
     * <p><b>校验链</b>：{@link UploadValidationChain#validate(String, MultipartFile[])}
     * 在 {@code try} 块内执行，{@code finally} 统一触发完成回调——无论成功、校验失败
     * （{@code UPLOAD_INVALID / UPLOAD_IN_PROGRESS}）还是业务异常（{@code storeOne} 抛错），
     * 用户级上传占位（{@code rl:upload:inflight:{userId}}）都被立即释放；TTL 仅兜底进程崩溃
     * / 释放失败场景（{@code inflight-ttl-seconds}）。若 {@code validate()} 置于 try 之外，
     * 校验失败时占位 key 会滞留 TTL 期内，同用户重试会被 42903 误拒——见 changelog 0009。</p>
     *
     * @param files     multipart 字段 files 的上传文件数组
     * @param requestId 客户端幂等键（可选，X-Request-Id 请求头；5s 内重复提交去重返回首次结果）
     * @return 上传成功后的图片 URL 列表（审核通过后对前端生效）
     */
    @SentinelResource(value = SentinelRateLimitConfig.UPLOAD_RESOURCE,
            entryType = EntryType.IN, blockHandler = "uploadBlocked")
    public List<String> upload(MultipartFile[] files, String requestId) {
        String userId = UserContextHolder.requireUserId();

        // 用户维度限流（机器维度已由 Sentinel 注解外层放行）
        if (!rateLimiter.tryAcquire(userId)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
        }

        // 幂等去重：同一 requestId（结合 userId）窗口内重复提交返回首次结果
        UploadIdempotency.IdempotencyOutcome outcome = idempotency.check(userId, requestId);
        if (outcome.isDuplicate()) {
            log.info("上传幂等命中，返回首次结果: userId={}, requestId={}, count={}",
                    userId, requestId, outcome.urls().size());
            return outcome.urls();
        }
        if (outcome.isInProgress()) {
            throw new BizException(ErrorCode.UPLOAD_IN_PROGRESS, "请求处理中，请勿重复提交");
        }

        UploadValidation context = null;
        try {
            context = validationChain.validate(userId, files);
            List<String> urls = new ArrayList<>(files.length);
            for (int i = 0; i < files.length; i++) {
                urls.add(storeOne(userId, files[i], context.format(i), requestId));
            }
            log.info("媒体上传受理: userId={}, count={}, requestId={}", userId, urls.size(), requestId);
            idempotency.store(userId, requestId, urls);
            return urls;
        } finally {
            // 覆盖 validate() 抛 UPLOAD_INVALID/UPLOAD_IN_PROGRESS 与 storeOne 异常：
            // 占位 key 必须释放，否则 TTL 兜底期内同用户重试会被 42903 误拒。
            if (context != null) {
                context.runCompletionCallbacks();
            }
        }
    }

    /**
     * Sentinel 限流回调（blockHandler）：规则命中时替代 {@link #upload} 执行。
     *
     * <p>签名契约（SentinelResourceAspect 约束）：与原方法同参 + 末尾 {@link BlockException}、
     * 同返回类型、同类 public 实例方法。不吞 BlockException 而是转成统一业务异常
     * {@link BizException}(RATE_LIMITED)，由全局异常处理器输出标准错误响应。</p>
     */
    public List<String> uploadBlocked(MultipartFile[] files, String requestId, BlockException e) {
        UserContext context = UserContextHolder.get();
        String userId = context != null ? context.userId() : "anonymous";
        log.warn("上传限流触发: userId={}, requestId={}, rule={}", userId, requestId, e.getClass().getSimpleName());
        throw new BizException(ErrorCode.RATE_LIMITED, "上传过于频繁，请稍后再试");
    }

    /** 单文件：可选处理 -&gt; 存储 -&gt; 发布事件，返回 URL（格式由校验链产出，不再重复嗅探）。 */
    private String storeOne(String userId, MultipartFile file, ImageFormat format, String requestId) {
        byte[] processed = maybeProcess(file, format);
        try (InputStream content = processed != null
                ? new ByteArrayInputStream(processed)
                : file.getInputStream()) {
            long size = processed != null ? processed.length : file.getSize();
            MediaStorageClient.StoredMedia stored =
                    storageClient.store(userId, format, content, size);
            eventPublisher.publish(new MediaUploadedEvent(
                    stored.mediaId(), userId, stored.url(), requestId, Instant.now()));
            return stored.url();
        } catch (IOException e) {
            log.error("读取上传内容失败: userId={}, size={}", userId, file.getSize(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传内容失败");
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
}
