package com.turbofeed.shared.result;

/**
 * 全局统一错误码。
 *
 * <p>分段约定：</p>
 * <ul>
 *   <li>0 —— 成功</li>
 *   <li>4xxxx —— 客户端侧错误（参数 / 鉴权 / 权限 / 限流等）</li>
 *   <li>5xxxx —— 服务端侧错误</li>
 * </ul>
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "成功"),

    /** 参数校验失败 */
    PARAM_ERROR(40001, "参数错误"),

    /** 未登录或登录态失效 */
    UNAUTHORIZED(40101, "未登录或凭证已失效"),

    /** 账号未注册（登录时手机号查无记录；演示 UX 优先，开放账号枚举） */
    ACCOUNT_NOT_REGISTERED(40102, "账号未注册，请先注册"),

    /** 已登录但无权限 */
    FORBIDDEN(40301, "无权限访问"),

    /** 资源不存在 */
    NOT_FOUND(40401, "资源不存在"),

    /** 触发限流 / 热点降级（Sentinel） */
    RATE_LIMITED(42901, "请求过于频繁，请稍后重试"),

    /** 上传文件为空、类型不符或超出大小限制 */
    UPLOAD_INVALID(42902, "上传文件不合法"),

    /** 同一用户已有上传任务进行中（并发护栏拒绝） */
    UPLOAD_IN_PROGRESS(42903, "已有上传任务进行中，请稍后再试"),

    /** 内容命中敏感词（描述/评论/文件名等走 AC 自动机扫描后 fail-closed 拒绝） */
    SENSITIVE_WORD_HIT(42904, "内容包含敏感词"),

    /** 系统内部错误 */
    INTERNAL_ERROR(50000, "系统内部错误"),

    /** 依赖组件（Redis / MQ / MySQL）不可用 */
    DEPENDENCY_UNAVAILABLE(50001, "依赖服务暂不可用，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
