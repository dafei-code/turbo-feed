package com.turbofeed.gateway.service.moderation;

/**
 * 内容检测的处置动作。
 *
 * <p><b>为什么不是二值（通过 / 拦截）</b>：二值模型逼着实现方在两端压注——放宽则漏放，
 * 收紧则误杀，中间那段「机器把握不大」的流量无处安放。分级动作把这段流量交给下游：
 * 拦不住的可以降权，拿不准的可以送人审。<b>误杀率与漏放率因此可以分别调，而不是同一个旋钮。</b></p>
 *
 * <p><b>当前强制程度</b>：</p>
 * <ul>
 *   <li>{@link #BLOCK} —— <b>已强制</b>：调用方拒绝发布，抛 {@code SENSITIVE_WORD_HIT}。</li>
 *   <li>{@link #REVIEW} —— <b>放行但标记需人审</b>：不拒绝发布，但裁定携带 REVIEW 动作，
 *       调用方据此把内容置入人工复审队列。与改造前「等同 BLOCK」的区别：内容不会在发布时
 *       被拒，用户能看到发布成功；人审队列异步介入，若复审不通过再走下架流程。
 *       这与抖音「先发后审」的产品语义一致——内容安全不是发布闸而是持续治理。</li>
 *   <li>{@link #LIMIT} —— <b>放行但标记需降权</b>：不拒绝发布，但裁定携带 LIMIT 动作，
 *       调用方据此在内容上标记降权标记，引擎侧推荐排序时据此降低分发权重。
 *       与改造前「等同 PASS」的区别：内容不再获得正常流量，而是限流分发。</li>
 *   <li>{@link #PASS} —— 放行，无附加动作。</li>
 * </ul>
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

    /**
     * 当前实现下是否会实际阻断发布。
     *
     * <p>P0 改造后只有 {@link #BLOCK} 阻断发布。{@link #REVIEW} 与 {@link #LIMIT}
     * 改为放行+标记——拒绝不是它们的语义，它们的语义是「放行但后续治理」
     * （REVIEW→人审队列，LIMIT→降权标记）。调用方通过 {@link ContentVerdict#action()}
     * 获取具体动作并据此路由。</p>
     */
    public boolean denies() {
        return this == BLOCK;
    }

    /**
     * 严重度排序（决策层取「最严重」动作的依据）。
     *
     * <p><b>为什么不用 {@code ordinal()}</b>：枚举声明顺序是为了<b>可读性</b>排的
     * （PASS / LIMIT / REVIEW / BLOCK），一旦后续有人为了别处方便调整顺序，
     * 严重度比较会静默反转。显式定级把「排序意图」和「声明顺序」解耦，
     * 顺序怎么调都不影响裁定结果。</p>
     *
     * <p>级别：{@code BLOCK(3) > REVIEW(2) > LIMIT(1) > PASS(0)}。
     * REVIEW 的 severity 高于 LIMIT：同一文本同时命中 REVIEW 级与 LIMIT 级词时，
     * 决策层取 REVIEW（人审比降权更重）。REVIEW 虽不再 {@code denies()}，
     * 但严重度仍高于 LIMIT——这是两个正交维度：denies 管是否阻断发布，
     * severity 管多命中时取哪个动作。</p>
     */
    public int severity() {
        return switch (this) {
            case BLOCK -> 3;
            case REVIEW -> 2;
            case LIMIT -> 1;
            case PASS -> 0;
        };
    }
}
