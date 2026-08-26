package com.turbofeed.shared.result;

/**
 * 全局统一返回结构。
 *
 * <p>所有 HTTP 接口的响应体统一使用本对象，JSON 形如：</p>
 * <pre>{@code
 * {
 *   "code": 0,
 *   "message": "成功",
 *   "data": { ... },
 *   "timestamp": 1751217600000
 * }
 * }</pre>
 *
 * <p>约定：{@code code = 0} 表示成功，非 0 表示失败，具体含义见 {@link ErrorCode}。</p>
 *
 * @param <T> 业务数据类型
 */
public final class Result<T> {

    /** 响应码，0 = 成功 */
    private int code;

    /** 人类可读的提示信息 */
    private String message;

    /** 业务数据，失败时通常为 null */
    private T data;

    /** 服务端响应时间戳（毫秒） */
    private long timestamp;

    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /** 成功（无数据） */
    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 成功（携带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 失败（使用预定义错误码及默认提示） */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败（使用预定义错误码，覆盖提示信息） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 失败（自定义 code 与提示） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 是否成功（code == 0） */
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
