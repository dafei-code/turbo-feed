package com.turbofeed.feedengine.ranking;

import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.shared.model.FeedItemView;

import java.util.List;
import java.util.Map;

/**
 * 粗排所需的页面级上下文（每页算一次，跨候选复用，避免每条候选各查一次）。
 *
 * <p>刻意只放「廉价信号」：长期 / 短期兴趣画像、session 序列、实时画像。
 * 不含曝光统计（DB）与作者健康分（Redis）——那些是精排的贵特征。</p>
 */
public record PreRankContext(Map<String, Double> interest,
                             Map<String, Double> shortInterest,
                             List<SessionSequenceService.SessionItem> session,
                             UserRealtimeProfile realtimeProfile) {

    /** 该候选与用户画像的轻量匹配分（与精排同口径，但无 DB 统计 / 无健康分）。 */
    public static PreRankFeatures matchOf(FeedItemView it, PreRankContext ctx) {
        double interest = 0d, shortTerm = 0d, session = 0d, realtime = 0d;
        List<String> tags = it.tags();
        if (tags != null) {
            Map<String, Double> intMap = ctx.interest();
            Map<String, Double> shortMap = ctx.shortInterest();
            if (intMap != null) {
                for (String t : tags) {
                    Double w = intMap.get(t);
                    if (w != null) {
                        interest += w;
                    }
                    Double sw = shortMap == null ? null : shortMap.get(t);
                    if (sw != null) {
                        shortTerm += sw;
                    }
                }
            }
            List<SessionSequenceService.SessionItem> sess = ctx.session();
            if (sess != null && !sess.isEmpty()) {
                for (SessionSequenceService.SessionItem si : sess) {
                    if (si.tags() == null || si.tags().isEmpty()) {
                        continue;
                    }
                    int hit = 0;
                    for (String t : tags) {
                        if (si.tags().contains(t)) {
                            hit++;
                        }
                    }
                    if (hit > 0) {
                        session += si.recencyWeight() * ((double) hit / tags.size());
                    }
                }
            }
            UserRealtimeProfile rt = ctx.realtimeProfile();
            if (rt != null && !rt.isEmpty()) {
                Map<String, Double> aff = rt.tagAffinity();
                for (String t : tags) {
                    Double a = aff.get(t);
                    if (a != null) {
                        realtime += a;
                    }
                }
            }
        }
        long recency = it.createdAt() == null ? 0L : it.createdAt().toEpochMilli();
        return new PreRankFeatures(recency, interest, shortTerm, session, realtime);
    }
}
