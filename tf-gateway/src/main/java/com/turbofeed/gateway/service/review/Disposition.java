package com.turbofeed.gateway.service.review;

/**
 * 违规处置建议（抖音式「梯度处置」核心枚举，changelog 待补）。
 *
 * <p>机器/人工判定出违规后，按 {@link DispositionPolicy} 把 (来源, 严重度) 映射成一个处置动作，
 * 而非「一律下架」：</p>
 * <ul>
 *   <li>{@link #INTERCEPT} 拦截下架（最重，原 {@code takeDownAndPenalize} 行为）；</li>
 *   <li>{@link #MONITOR} 限公域/仅自己可见（软处置：移出推荐公域、作者仍可在 media/mine 自见，不扣分不处罚）；</li>
 *   <li>{@link #REVIEW} 转人工二次研判（Tier1/2/3 复审队列语义，暂由既有任务流程承载）；</li>
 *   <li>{@link #PASS} 放行（无动作）。</li>
 * </ul>
 *
 * <p>与 feature-match 方案的 {@code Disposition} 同源：机审复扫命中 MID 走 MONITOR 而非直接下架，
 * 解决「宁可错杀」——对齐抖音「先是流量隔离，而非删除」。</p>
 */
public enum Disposition {

    /** 拦截下架（最重处置）。 */
    INTERCEPT,

    /** 限公域 / 仅自己可见（软处置，网关独做：移出推荐公域、作者自见、不扣分不处罚）。 */
    MONITOR,

    /** 转人工二次研判（复用复审队列语义）。 */
    REVIEW,

    /** 放行（无动作）。 */
    PASS
}
