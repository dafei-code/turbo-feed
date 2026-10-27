package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.FeedFilterContext;
import com.turbofeed.feedengine.ranking.UserRealtimeProfile;
import com.turbofeed.shared.model.FeedItemView;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次 {@code recommend} 请求的共享上下文（召回与重排阶段共用）。
 *
 * <p>不可变部分（userId/page/limit/cursor/画像/parsed 等）在 {@link RecommendationPipeline} 构造时一次性填入；
 * 可写部分（hotKeys/interestKeys/vectorKeys/followKeys 共享排除集、authorHealthCache 评分缓存、
 * filterCtx/filterChainEnabled 审核上下文）随漏斗推进逐步填充——排除集由召回通道写、池解析与装配读，
 * 审核上下文在池解析后、装配前由 pipeline 写入供重排阶段使用。</p>
 */
public class RecallContext {

    public final String userId;
    public final int page;
    public final int limit;
    public final FeedCursor cursor;
    public final Map<String, Double> interest;
    public final Map<String, Double> shortInterest;
    public final List<SessionSequenceService.SessionItem> session;
    public final Set<String> negative;
    public final UserRealtimeProfile realtimeProfile;
    public final Set<String> followeeAuthors;
    /** 池解析后的候选（FeedItemView，已按池压缩），供向量召回取全量候选。构造时为空，池解析后写入。 */
    public Map<Integer, List<FeedItemView>> parsed;
    public final RecallSupport support;

    // ===== 召回通道共享的跨通道排除集（LinkedHashSet 保序且去重）=====
    public final Set<String> hotKeys = new LinkedHashSet<>();
    public final Set<String> interestKeys = new LinkedHashSet<>();
    public final Set<String> vectorKeys = new LinkedHashSet<>();
    public final Set<String> followKeys = new LinkedHashSet<>();
    /** 评分阶段懒加载的作者健康分缓存（本页共享）。 */
    public final Map<String, Double> authorHealthCache = new LinkedHashMap<>();

    // ===== 池解析后写入：审核过滤上下文 =====
    public boolean filterChainEnabled;
    public FeedFilterContext filterCtx;

    public RecallContext(String userId, int page, int limit, FeedCursor cursor,
                         Map<String, Double> interest, Map<String, Double> shortInterest,
                         List<SessionSequenceService.SessionItem> session, Set<String> negative,
                         UserRealtimeProfile realtimeProfile, Set<String> followeeAuthors,
                         Map<Integer, List<FeedItemView>> parsed, RecallSupport support) {
        this.userId = userId;
        this.page = page;
        this.limit = limit;
        this.cursor = cursor;
        this.interest = interest;
        this.shortInterest = shortInterest;
        this.session = session;
        this.negative = negative;
        this.realtimeProfile = realtimeProfile;
        this.followeeAuthors = followeeAuthors;
        this.parsed = parsed;
        this.support = support;
    }
}
