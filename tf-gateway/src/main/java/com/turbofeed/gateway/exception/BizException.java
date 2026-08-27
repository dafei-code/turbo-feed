package com.turbofeed.gateway.exception;

import com.turbofeed.shared.result.ErrorCode;

/**
 * 业务异常：携带全局错误码，由 {@link GlobalExceptionHandler} 统一翻译为 {@code Result} 响应。
 *
 * <p>分层约定：业务代码（Service / Resolver）以抛出本异常表达失败语义，
 * 禁止在 Controller 里散落 {@code Result.fail(...)} 拼装错误响应。</p>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 使用预定义错误码及默认提示。 */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 使用预定义错误码，覆盖提示信息（面向用户可读的具体原因）。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
