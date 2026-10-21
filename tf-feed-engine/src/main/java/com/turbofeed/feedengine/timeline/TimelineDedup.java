package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;

import java.util.HashSet;
import java.util.Set;

/**
 * 整页级去重（G6「整页去重」核心机制）。
 *
 * <p>按 {@link FeedItemView#timelineKey()} 记录本页已入流内容，避免同一内容在
 * 关注 / 兴趣 / 向量 / 热点 / 流量池 / 冷启动<b>多路召回</b>中于同一页重复出现
 * （如关注作者内容同时落入关注位与流量池位）；G9 全局占比统计亦因此不被重复计数。</p>
 *
 * <p><b>与审核拦截正交</b>：本类只做「重复过滤」，不做「审核拦截」。调用方（
 * {@code FeedTimelineStore#readPage}）把 {@link #accept} 与 {@code isModerationBlocked}
 * 用 {@code ||} 组合——审核被拦截的内容<b>不会</b>登记到去重集合（短路求值），
 * 即「被拦的内容不占用去重名额」，与改造前语义完全一致。本类因此可被纯单测覆盖，
 * 无需 Redis / 审核信号客户端。</p>
 */
public final class TimelineDedup {

    private final Set<String> seen = new HashSet<>();

    /**
     * 尝试接纳一个内容入流。
     *
     * @return {@code true}=该 timelineKey 本页首次出现（调用方应入流）；
     *         {@code false}=重复（调用方应跳过）
     */
    public boolean accept(FeedItemView item) {
        return seen.add(item.timelineKey());
    }

    /** 本页已去重接纳的不同内容数（仅供观测 / 测试断言）。 */
    public int distinctCount() {
        return seen.size();
    }
}
