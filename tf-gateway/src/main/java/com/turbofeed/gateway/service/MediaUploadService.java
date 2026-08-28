package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.event.MediaEventPublisher;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.processing.ImageProcessingChain;
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
 * 内容图片上传服务：批量校验 -&gt; 逐文件处理 -&gt; 存储端口写入 -&gt; 发布上传事件。
 *
 * <p>主链路只有四个薄方法，职责单一：{@link #upload} 编排批量、{@link #validateBatch}
 * 批量级校验、{@link #validate} 单文件校验（含 Magic Number 真实格式识别）、
 * {@link #storeOne} 单文件「处理 -&gt; 存储 -&gt; 发事件」。</p>
 *
 * <p>校验任一失败抛 {@link BizException}(UPLOAD_INVALID)，由全局异常处理器统一响应；
 * 文件头嗅探采用<b>流式读取固定 16 字节</b>，默认路径杜绝 {@code getBytes()} 全量加载
 * 进内存，仅当处理链启用（{@code processing-enabled: true}）时才全量读取交给 ImageIO。</p>
 *
 * <p>UGC 口径下上传成功仅代表受理，进入待审核态（PENDING），审核通过后才对前端可见。</p>
 */
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    /** 文件头嗅探长度：覆盖全部白名单格式 Magic Number 的最大偏移（RIFF....WEBP 需 12 字节）。 */
    private static final int HEADER_SNIFF_SIZE = 16;

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;
    private final MediaEventPublisher eventPublisher;
    private final ImageProcessingChain processingChain;

    /**
     * 批量上传图片，返回可访问 URL 列表。
     *
     * <p>归属用户不经过方法参数：从请求线程上下文
     * {@link UserContextHolder#requireUserId()} 获取（JWT 验签写入，客户端无法指定他人；
     * 未携带有效令牌即 UNAUTHORIZED）。单测可通过 {@code UserContextHolder.set/clear}
     * 构造身份。</p>
     *
     * @param files     multipart 字段 files 的上传文件数组
     * @param requestId 客户端幂等键（可选，X-Request-Id 请求头；幂等去重落地时使用）
     * @return 上传成功后的图片 URL 列表（审核通过后对前端生效）
     */
    public List<String> upload(MultipartFile[] files, String requestId) {
        String userId = UserContextHolder.requireUserId();
        validateBatch(files);
        List<String> urls = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            urls.add(storeOne(userId, file, requestId));
        }
        log.info("媒体上传受理: userId={}, count={}, requestId={}", userId, urls.size(), requestId);
        return urls;
    }

    /** 批量级校验：非空且不超过单次张数上限。 */
    private void validateBatch(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "请选择至少一张图片");
        }
        if (files.length > properties.getMaxBatchCount()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单次最多上传 " + properties.getMaxBatchCount() + " 张");
        }
    }

    /** 单文件：校验 -&gt; 可选处理 -&gt; 存储 -&gt; 发布事件，返回 URL。 */
    private String storeOne(String userId, MultipartFile file, String requestId) {
        ImageFormat format = validate(file);
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

    /** 单文件级校验：空文件 -&gt; 大小上限 -&gt; 真实格式（Magic Number），全部通过返回格式。 */
    private ImageFormat validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "存在空文件");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单文件不得超过 " + properties.getMaxFileSize().toMegabytes() + "MB");
        }
        return detectFormat(file);
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

    /** 流式嗅探文件头判定真实格式；SVG / 伪造扩展名 / 非白名单一律拒绝。 */
    private ImageFormat detectFormat(MultipartFile file) {
        byte[] header = new byte[HEADER_SNIFF_SIZE];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            log.error("读取文件头失败: size={}", file.getSize(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传内容失败");
        }
        // 正常图片不可能小于 16 字节；不足即判定为非法内容
        if (read < HEADER_SNIFF_SIZE) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "仅支持 jpg/png/gif/webp");
        }
        ImageFormat format = ImageFormat.detect(header);
        if (format == null) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "仅支持 jpg/png/gif/webp");
        }
        return format;
    }
}
