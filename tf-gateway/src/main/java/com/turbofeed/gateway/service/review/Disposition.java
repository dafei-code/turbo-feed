package com.turbofeed.gateway.service.review;

/**
 * 违规处置建议（抖音式「梯度处置」核心枚举）。
 *
 * <p>机器/人工判定出违规后，按 {@link DispositionPolicy} 把 (来源, 严重度) 映射成一个处置动作，
 * 而非「一律下架」：</p>
 * <ul>
 *   <li>{@link #INTERCEPT} 拦截下架（最重，原 {@code takeDownAndPenalize} 行为）；</li>
 *   <li>{@link #MONITOR} 限公域 / 仅自己可见（软处置：移出推荐公域与搜索结果、作者仍可在
 *       {@code media/mine} 自见，不扣分不处罚）；</li>
 *   <li>{@link #DEMOTE} 内容级轻降权（ADR-0014）：<b>仍留在公域发现流、仍可被搜索、仍可播放</b>，
 *       仅在精排阶段乘一个降权系数；适用于机审「边界敏感但未确认违规」的 LIMIT 裁定；</li>
 *   <li>{@link #REVIEW} 转人工二次研判（Tier1/2/3 复审队列语义，暂由既有任务流程承载）；</li>
 *   <li>{@link #PASS} 放行（无动作）。</li>
 * </ul>
 *
 * <p><b>两处「DEMOTE」不是一回事（务必区分）</b>：本枚举的 DEMOTE 是<b>内容级</b>处置，
 * 写在 {@code tf:mod:media:{postId}} 的 {@code action} 字段；账号健康分档位里的
 * {@code tier=DEMOTE} 是<b>作者级</b>信号，写在 {@code tf:mod:account:{userId}} 的 {@code tier} 字段。
 * 引擎侧二者各自有独立系数（{@code contentDemoteScale} / {@code demoteScale}），最终相乘作用于精排分。</p>
 *
 * <p><b>消费契约（跨服务，改动需同步引擎与搜索）</b>：
 * <ul>
 *   <li>召回层硬剔除：仅 INTERCEPT / MONITOR；</li>
 *   <li>搜索结果剔除：仅 INTERCEPT / MONITOR；</li>
 *   <li>播放前复校打断：仅 INTERCEPT；</li>
 *   <li>精排降权：仅 DEMOTE（另有作者级 DEMOTE 档独立生效）。</li>
 * </ul>
 * 新增档位时，四处必须一起确认，否则会出现「信号写了但没人认」或「被误当成放行/误下架」。</p>
 *
 * <p>与 feature-match 方案的 {@code Disposition} 同源：机审复扫命中 MID 走 MONITOR 而非直接下架，
 * 解决「宁可错杀」——对齐抖音「先是流量隔离，而非删除」。</p>
 */
public enum Disposition {

    /** 拦截下架（最重处置）。 */
    INTERCEPT,

    /** 限公域 / 仅自己可见（软处置，网关独做：移出推荐公域、作者自见、不扣分不处罚）。 */
    MONITOR,

    /**
     * 内容级轻降权（ADR-0014）：不摘时间线、不下架、不扣分、不处罚；仅让引擎在精排乘
     * {@code contentDemoteScale}。搜索与播放均不受影响。
     *
     * <p>由内容安全 {@code LIMIT} 裁定产生（{@code ModerationDownstreamRouter}），不是
     * {@link DispositionPolicy} 默认矩阵的产物；默认矩阵只产出 INTERCEPT / MONITOR，
     * 故对既有举报/复审处置链路<b>零行为回归</b>。</p>
     */
    DEMOTE,

    /** 转人工二次研判（复用复审队列语义）。 */
    REVIEW,

    /** 放行（无动作）。 */
    PASS
}
