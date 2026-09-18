package com.turbofeed.gateway.service.penalty;

/**
 * 违规发现来源。
 */
public enum ViolationSource {

    /** 机审（自动）。 */
    MACHINE(1),
    /** 用户举报。 */
    HUMAN_REPORT(2),
    /** 人工审核。 */
    HUMAN_REVIEW(3),
    /** 申诉翻案（用于回滚/修正，不应再升级）。 */
    APPEAL_OVERTURN(4);

    private final int code;

    ViolationSource(int code) {
        this.code = code;
    }

    /** 持久化编码（violation_record.source 列）。 */
    public int code() {
        return code;
    }

    public static ViolationSource fromCode(int c) {
        for (ViolationSource v : values()) {
            if (v.code == c) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知违规来源编码: " + c);
    }
}
