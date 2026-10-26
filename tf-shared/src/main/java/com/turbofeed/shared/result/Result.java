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

    /**
     * 是否成功（code == 0）。
     *
     * <p>⚠️ <b>方法名刻意不是 {@code isSuccess()}</b>：Jackson 会把 {@code isXxx()} 当作
     * boolean getter 序列化出去，那样每个响应体都会凭空多出一个 {@code "success": true} 字段；
     * 而本类既无 {@code success} 字段也无对应 setter，写得出、读不回——一旦有人打开
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}，反序列化 {@code Result} 会整体失败。
     * 本模块零第三方依赖（不能用 {@code @JsonIgnore}），因此从命名上绕开：去掉 {@code is} 前缀后
     * Jackson 不再把它当属性。由 {@code SharedContractJsonTest} 的「幽灵字段护栏」锁死。</p>
     */
    public boolean succeeded() {
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
