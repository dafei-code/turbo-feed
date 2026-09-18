package com.turbofeed.gateway.service.penalty;

/**
 * 账号处罚态（硬执行维度，与 account_credit 的软声誉分离）。
 * 读路径先查此状态，{@link #BANNED_TEMP}/{@link #BANNED_PERM} 直接拒写。
 */
public enum AccountPenaltyStatus {

    /** 正常。 */
    NORMAL(0),
    /** 警告（软性，仍可写）。 */
    WARN(1),
    /** 临时封禁（ban_until 之前不可写）。 */
    BANNED_TEMP(2),
    /** 永久封禁（不可写）。 */
    BANNED_PERM(3);

    private final int code;

    AccountPenaltyStatus(int code) {
        this.code = code;
    }

    /** 持久化编码（account_penalty.status 列）。 */
    public int code() {
        return code;
    }

    public static AccountPenaltyStatus fromCode(int c) {
        for (AccountPenaltyStatus v : values()) {
            if (v.code == c) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知处罚态编码: " + c);
    }
}
