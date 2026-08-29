package com.turbofeed.gateway.service.validation;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.ImageFormat;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 链环 2（Order 2）：文件约束校验——批量数 → 单文件（空文件 / 大小 / Magic Number）。
 *
 * <p>嗅探出的真实格式经 {@link UploadValidation#recordFormat(int, ImageFormat)}
 * 写回上下文，主链路 {@code storeOne} 直接取用，不在存储阶段二次读文件头。</p>
 *
 * <p>校验不信任扩展名 / Content-Type：流式 {@code readNBytes} 读文件头前 16 字节
 * （覆盖 RIFF....WEBP 偏移 8）做 Magic Number 识别，白名单 jpg / png / gif / webp，
 * <b>刻意排除 SVG</b>（XSS 载体）。默认路径零全量加载（不调 {@code getBytes()}）。</p>
 */
@Component
@Order(2)
public class FileConstraintValidator extends UploadValidator {

    private static final Logger log = LoggerFactory.getLogger(FileConstraintValidator.class);

    /** 文件头嗅探长度：覆盖全部白名单格式 Magic Number 的最大偏移（RIFF....WEBP 需 12 字节）。 */
    private static final int HEADER_SNIFF_SIZE = 16;

    private final MediaProperties properties;

    public FileConstraintValidator(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doValidate(UploadValidation context) {
        MultipartFile[] files = context.files();

        // 批量级：非空且不超过单次张数上限
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "请选择至少一张图片");
        }
        if (files.length > properties.getMaxBatchCount()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单次最多上传 " + properties.getMaxBatchCount() + " 张");
        }

        // 单文件级：空文件 → 大小上限 → Magic Number；格式写回上下文供主链路取用
        for (int i = 0; i < files.length; i++) {
            context.recordFormat(i, validateFile(files[i]));
        }
    }

    /** 单文件级校验，全部通过返回真实格式（Magic Number 嗅探结果）。 */
    private ImageFormat validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID, "存在空文件");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BizException(ErrorCode.UPLOAD_INVALID,
                    "单文件不得超过 " + properties.getMaxFileSize().toMegabytes() + "MB");
        }
        return detectFormat(file);
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
