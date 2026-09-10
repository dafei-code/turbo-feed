package com.turbofeed.gateway.service.review.credit;

/**
 * 账号信用等级（抖音式「信用分级决定先发/先审」的核心）。
 *
 * <p>等级驱动上传后的审核口径与初始流量池：</p>
 * <ul>
 *   <li>{@link #L0} —— 新号/低信用：<b>先审后放</b>（机审通过仍进 PENDING 等人审），不进公域。</li>
 *   <li>{@link #L1} —— 普通：<b>先发后审</b>，发布即进小流量池（限流观察，靠举报/人审兜底）。</li>
 * <li>{@link #L2} —— 高信用：<b>先发后审</b>，发布即进大流量池（初始曝光更高）。</li>
 * </ul>
 *
 * <p>{@link #poolLevel()} 返回该等级对应的初始公域流量池层级（见 {@code FeedTimelineStore}）：
 * L0=0（不入池）/ L1=1（小池）/ L2=3（大池）。</p>
 */
public enum CreditLevel {

    /** 新号/低信用：先审后放，不进公域。 */
    L0(0, 0),
    /** 普通：先发后审，小流量池。 */
    L1(1, 1),
    /** 高信用：先发后审，大流量池。 */
    L2(2, 3);

    private final int code;
    private final int poolLevel;

    CreditLevel(int code, int poolLevel) {
        this.code = code;
        this.poolLevel = poolLevel;
    }

    /** 持久化编码（account_credit.level 列）。 */
    public int code() {
        return code;
    }

    /** 初始公域流量池层级（0=不入池）。 */
    public int poolLevel() {
        return poolLevel;
    }

    public static CreditLevel fromCode(int c) {
        return switch (c) {
            case 2 -> L2;
            case 0 -> L0;
            default -> L1;
        };
    }
}
