package com.turbofeed.gateway.service.penalty;

/**
 * 违规严重度。{@link #CRITICAL} 直接触发最重处罚（封禁）。
 */
public enum ViolationSeverity {

    LOW(1),
    MID(2),
    HIGH(3),
    CRITICAL(4);

    private final int code;

    ViolationSeverity(int code) {
        this.code = code;
    }

    /** 持久化编码（violation_record.severity 列）。 */
    public int code() {
        return code;
    }

    public static ViolationSeverity fromCode(int c) {
        for (ViolationSeverity v : values()) {
            if (v.code == c) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知违规严重度编码: " + c);
    }
}
