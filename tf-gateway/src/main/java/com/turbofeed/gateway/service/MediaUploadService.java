package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.storage.MediaStorageClient;
import com.turbofeed.shared.result.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体上传服务：批量校验 -&gt; 真实格式识别 -&gt; 存储端口写入 -&gt; URL 返回。
 *
 * <p>校验链（任一失败抛 {@link BizException}(UPLOAD_INVALID)，由全局异常处理器统一响应）：
 * 批量数量 -&gt; 空文件 -&gt; 单文件大小 -&gt; Magic Number 真实类型。
 * 文件头嗅探采用<b>流式读取固定 16 字节</b>，杜绝 {@code getBytes()} 全量加载进内存。
 * UGC 口径下上传成功仅代表受理，进入待审核态（PENDING），审核通过后才对前端可见。</p>
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    /** 文件头嗅探长度：覆盖全部白名单格式 Magic Number 的最大偏移（RIFF....WEBP 需 12 字节）。 */
    private static final int HEADER_SNIFF_SIZE = 16;

    private final MediaProperties properties;
    private final MediaStorageClient storageClient;

    public MediaUploadService(MediaProperties properties, MediaStorageClient storageClient) {
        this.properties = properties;
        this.storageClient = storageClient;
    }

    /**
     * 批量上传图片，返回可访问 URL 列表。
     *
     * <p>归属用户不经过方法参数：从请求线程上下文
     * {@link UserContextHolder#requireUserId()} 获取（JWT 验签写入，客户端无法指定他人；
     * 未携带有效令牌即 UNAUTHORIZED）。单测可通过 {@code UserContextHolder.set/clear}
     * 构造身份。</p>
     *
     * @param files  multipart 字段 files 的上传文件数组
     * @return 上传成功后的图片 URL 列表（审核通过后对前端生效）
     */
    public List<String> upload(MultipartFile[] files) {
        String userId = UserContextHolder.requireUserId();
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "请选择至少一张图片");
        }
        if (files.length > properties.getMaxBatchCount()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单次最多上传 " + properties.getMaxBatchCount() + " 张");
        }
        List<String> urls = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            urls.add(storeOne(userId, file));
        }
        log.info("媒体上传受理: userId={}, count={}, urls={}", userId, files.length, urls.size());
        return urls;
    }

    /** 单文件校验与存储。 */
    private String storeOne(String userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "存在空文件");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单文件不得超过 " + properties.getMaxFileSize().toMegabytes() + "MB");
        }
        ImageFormat format = detectFormat(file);
        try (InputStream content = file.getInputStream()) {
            MediaStorageClient.StoredMedia stored =
                    storageClient.store(userId, format, content, file.getSize());
            return stored.url();
        } catch (IOException e) {
            log.error("读取上传内容失败: userId={}, size={}", userId, file.getSize(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传内容失败");
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
