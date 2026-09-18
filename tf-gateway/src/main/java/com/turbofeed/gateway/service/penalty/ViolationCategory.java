package com.turbofeed.gateway.service.penalty;

/**
 * 违规类目（抖音式多场景拆分）。随业务增长可在此追加，命中即写入 {@code violation_record.category}。
 *
 * <p>区分「内容违规」（单条处置）与「账号/行为违规」（账号级处置）只是语义归类，
 * 二者都进入同一处罚累积通道，由 {@link PenaltyEscalationPolicy} 决定升级。</p>
 */
public enum ViolationCategory {

    /** 色情低俗。 */
    CONTENT_PORN(1),
    /** 政治敏感。 */
    CONTENT_POLITICS(2),
    /** 暴力血腥。 */
    CONTENT_VIOLENCE(3),
    /** 广告引流 / 垃圾营销。 */
    SPAM_AD(4),
    /** 人身攻击 / 辱骂。 */
    ABUSE(5),
    /** 账号安全（盗号 / 欺诈）。 */
    ACCOUNT_SECURITY(6),
    /** 刷量作弊 / 数据造假。 */
    BRUTE_FRAUD(7);

    private final int code;

    ViolationCategory(int code) {
        this.code = code;
    }

    /** 持久化编码（violation_record.category 列）。 */
    public int code() {
        return code;
    }

    public static ViolationCategory fromCode(int c) {
        for (ViolationCategory v : values()) {
            if (v.code == c) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知违规类目编码: " + c);
    }
}
