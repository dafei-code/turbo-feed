package com.turbofeed.gateway.service.penalty;

/**
 * 对本次违规采取的具体处置动作（写入 violation_record.action_taken，亦用于统计）。
 */
public enum PenaltyAction {

    /** 无（仅记录）。 */
    NONE(0),
    /** 警告（软性，仍可写）。 */
    WARN(1),
    /** 信用扣分（由 account_credit 处理，本列只记动作）。 */
    DEDUCT(2),
    /** 加严队列（先审后放，由 account_credit.strict_queue_flag 处理）。 */
    STRICT_QUEUE(3),
    /** 临时封禁。 */
    BAN_TEMP(4),
    /** 永久封禁。 */
    BAN_PERM(5),
    /** 解除封禁（撤销 / 申诉翻案）。<b>非违规动作</b>，仅用于审计留痕。 */
    LIFT(6);

    private final int code;

    PenaltyAction(int code) {
        this.code = code;
    }

    /** 持久化编码（violation_record.action_taken 列）。 */
    public int code() {
        return code;
    }

    public static PenaltyAction fromCode(int c) {
        for (PenaltyAction v : values()) {
            if (v.code == c) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知处置动作编码: " + c);
    }
}
