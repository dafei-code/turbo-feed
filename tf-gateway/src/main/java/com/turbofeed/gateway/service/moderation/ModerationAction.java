package com.turbofeed.gateway.service.moderation;

/**
 * 内容检测的处置动作。
 *
 * <p><b>为什么不是二值（通过 / 拦截）</b>：二值模型逼着实现方在两端压注——放宽则漏放，
 * 收紧则误杀，中间那段「机器把握不大」的流量无处安放。分级动作把这段流量交给下游：
 * 拦不住的可以降权，拿不准的可以送人审。<b>误杀率与漏放率因此可以分别调，而不是同一个旋钮。</b></p>
 *
 * <p><b>当前强制程度</b>（诚实标注，避免误以为已全部生效）：</p>
 * <ul>
 *   <li>{@link #BLOCK} —— <b>已强制</b>：调用方拒绝发布，抛 {@code SENSITIVE_WORD_HIT}。</li>
 *   <li>{@link #REVIEW} —— <b>暂等价于 BLOCK</b>：同样拒绝，但日志与计数单独归类。
 *       真正语义应为「不拒绝、转人工复审队列」，需与审核状态机联通，属后续阶段。</li>
 *   <li>{@link #LIMIT} —— <b>暂等价于 PASS</b>：放行并单独计数。真正语义应为「放行但标记降权」，
 *       需 feed 侧支持按标记降权，属后续阶段。</li>
 *   <li>{@link #PASS} —— 放行。</li>
 * </ul>
 *
 * <p>把未接通的动作显式标注出来，而不是假装它们已生效——否则运维会按「REVIEW 会进人审队列」
 * 的预期配置策略，实际却把内容直接拒了。</p>
 */
public enum ModerationAction {

    /** 放行。 */
    PASS,

    /** 放行但降权（尚未接通 feed 侧标记，当前等同 PASS）。 */
    LIMIT,

    /** 转人工复审（尚未接通审核队列，当前等同 BLOCK）。 */
    REVIEW,

    /** 拒绝发布。 */
    BLOCK;

    /** 当前实现下是否会实际阻断发布。 */
    public boolean denies() {
        return this == BLOCK || this == REVIEW;
    }
}
