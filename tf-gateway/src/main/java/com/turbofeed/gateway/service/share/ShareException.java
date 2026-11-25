package com.turbofeed.gateway.service.share;

import com.turbofeed.shared.result.ErrorCode;

/** 分享业务异常：携带可直接映射到 HTTP 响应的错误码。 */
public class ShareException extends RuntimeException {

    private final ErrorCode errorCode;

    public ShareException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
