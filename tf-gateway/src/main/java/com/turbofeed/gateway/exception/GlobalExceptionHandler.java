package com.turbofeed.gateway.exception;

import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器：把异常统一翻译为 {@link Result} 结构，Controller 不感知错误拼装。
 *
 * <p>处理策略：
 * <ul>
 *   <li>{@link BizException}——预期内失败，warn 记录，按错误码返回；</li>
 *   <li>框架层异常（上传超限 / 缺参）——映射到语义化错误码；</li>
 *   <li>未知异常——error 记录完整堆栈，对外仅返回通用 INTERNAL_ERROR，不泄漏内部细节。</li>
 * </ul></p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：预期内失败，按携带的错误码与信息返回。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /** 上传体积超出 multipart 框架层限制（业务层限额之外的前置兜底）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传体积超出框架层限制: limit={}B", e.getMaxUploadSize());
        return Result.fail(ErrorCode.UPLOAD_INVALID, "文件大小超出限制");
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(ErrorCode.PARAM_ERROR, "缺少必填参数: " + e.getParameterName());
    }

    /**
     * 静态资源不存在（如浏览器自动请求的 /favicon.ico 项目未提供）。
     * 属客户端 404，不应计入"未处理异常"，故不打 ERROR 堆栈，仅按 404 错误码返回。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在(404): {}", e.getMessage());
        return Result.fail(ErrorCode.NOT_FOUND);
    }

    /** 兜底：未知异常统一 50000，详细信息只进日志，不外泄给客户端。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
