package com.turbofeed.gateway.service.signal;

/**
 * 审核信号外供 KV 的读视图（抖音式「审核与推荐解耦」：上游推荐系统消费这些视图，
 * 不直连 turbo-feed 的 MySQL 审核库）。
 *
 * <p>信号由 {@link ModerationSignalService} 写入 Redis（命名空间 {@code tf:mod:}，可由配置覆盖），
 * 本类只定义上游能读到的契约结构。</p>
 */
public final class ModerationSignalView {

    private ModerationSignalView() {
    }

    /** 内容处置信号：某帖当前处置动作（INTERCEPT 下架 / MONITOR 限公域 / REVIEW / PASS）。 */
    public record DispositionView(String action, String severity, String source, long ts) {
    }

    /** 账号健康分信号：内容作者当前健康分与梯级（供推荐召回过滤 + 排序降权）。 */
    public record HealthView(int score, String tier, long ts) {
    }

    /** 举报人信用信号：举报行为信用分（恶意举报者其举报不计入复审升级，见 P1 #131）。 */
    public record CreditView(int score, boolean banned, long ts) {
    }
}
