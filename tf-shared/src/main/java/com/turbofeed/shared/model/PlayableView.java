package com.turbofeed.shared.model;

/**
 * 播放前可放性复校的跨服务契约（抖音式「非公平态 5s 刷新」）。
 *
 * <p><b>为什么独立成契约而不是塞进 {@link FeedItemView}</b>：{@code FeedItemView} 会被序列化进
 * {@code tf:feed:tl:*} 时间线成员串，若在写入时刻附带 playable，既是过期快照（播放前复校的意义
 * 正是捕捉「预取 → 滚动」之间的状态翻转），又会污染历史成员串。故播放前复校走独立轻量端点，
 * 本记录只承载「某一刻的复校结论」。</p>
 *
 * <p><b>序列化</b>：与 {@link FeedItemView} 同处 tf-shared（已开启 {@code -parameters}），record 构造器
 * 形参名随 class 暴露，下游 Jackson 可直接反序列化，无需 {@code @JsonProperty}。</p>
 *
 * @param postId   帖子唯一标识（与时间线 {@code timelineKey} 一致）
 * @param playable 是否可播放：false = 内容已下架 / 作者被封禁或健康分过低，客户端应渲染占位 + 5s 跳下一条
 * @param reason   不可见原因（playable=true 时为 null）：{@link #REASON_INTERCEPT} /
 *                {@link #REASON_ACCOUNT_BANNED} / {@link #REASON_ACCOUNT_LOW_SCORE}
 */
public record PlayableView(String postId, boolean playable, String reason) {

    /** 内容处置拦截（下架 / 审核拦截 / 申诉中）：对应 {@code tf:mod:media} 的 INTERCEPT。 */
    public static final String REASON_INTERCEPT = "INTERCEPT";
    /** 作者已封禁：对应 {@code tf:mod:account} 的 tier=BANNED。 */
    public static final String REASON_ACCOUNT_BANNED = "ACCOUNT_BANNED";
    /** 作者健康分低于阈值（默认 <60）：对应 {@code tf:mod:account} 的 score < dropBelowScore。 */
    public static final String REASON_ACCOUNT_LOW_SCORE = "ACCOUNT_LOW_SCORE";

    /** fail-open 构造：KV 异常 / 缺失时一律「可播放」，绝不误杀用户。 */
    public static PlayableView playable(String postId) {
        return new PlayableView(postId, true, null);
    }

    /** 不可见构造。 */
    public static PlayableView blocked(String postId, String reason) {
        return new PlayableView(postId, false, reason);
    }
}
