package com.turbofeed.gateway.service.validation;

import com.turbofeed.gateway.service.ImageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传校验上下文：沿责任链单向传递的可变载体（Pipeline Context 模式）。
 *
 * <p>两种"沿链累积"的数据通道：</p>
 * <ul>
 *   <li><b>校验结果写回</b>——{@link FileConstraintValidator} 嗅探出的真实格式
 *       {@link #recordFormat(int, ImageFormat)} 逐文件写回，主链路 {@code storeOne}
 *       直接取用，避免存储阶段二次读取文件头；</li>
 *   <li><b>完成回调注册</b>——{@link ConcurrentUploadValidator} 占位成功后注册
 *       {@link #onCompletion(Runnable)} 释放动作，由 {@code MediaUploadService#upload}
 *       的 finally 统一触发：无论校验失败、业务异常还是正常返回，占位都被释放。</li>
 * </ul>
 *
 * <p>非线程安全：单请求单线程使用，与请求生命周期一致。</p>
 */
public final class UploadValidation {

    private static final Logger log = LoggerFactory.getLogger(UploadValidation.class);

    private final String userId;
    private final MultipartFile[] files;
    private final ImageFormat[] detectedFormats;
    private final List<Runnable> completionCallbacks = new ArrayList<>();

    UploadValidation(String userId, MultipartFile[] files) {
        this.userId = userId;
        this.files = files;
        this.detectedFormats = new ImageFormat[files == null ? 0 : files.length];
    }

    /** 归属用户（JWT 线程上下文取出的用户标识）。 */
    public String userId() {
        return userId;
    }

    /** 待上传文件数组（可能为 null/空，合法性由 {@link FileConstraintValidator} 裁定）。 */
    public MultipartFile[] files() {
        return files;
    }

    /** 读取第 index 个文件嗅探出的真实格式（须在 {@link FileConstraintValidator} 通过后调用）。 */
    public ImageFormat format(int index) {
        return detectedFormats[index];
    }

    /** 写回第 index 个文件的嗅探格式（仅 validation 包内校验器调用）。 */
    void recordFormat(int index, ImageFormat format) {
        this.detectedFormats[index] = format;
    }

    /** 注册请求结束时的清理动作（仅 validation 包内校验器调用；如占位 key 释放）。 */
    void onCompletion(Runnable callback) {
        this.completionCallbacks.add(callback);
    }

    /** 触发全部完成回调：逐个执行、各自隔离异常——一个清理失败不得影响其余清理。 */
    public void runCompletionCallbacks() {
        for (Runnable callback : completionCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("上传上下文清理动作执行失败（占位依赖 TTL 兜底过期）: userId={}", userId, e);
            }
        }
    }
}
