package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link PreRankFilter} 隔离单测（G1 粗排截断层）。
 *
 * <p>纯逻辑：mock {@link PreRankModel}，用「得分 = recency 毫秒」的确定性 Answer 让候选按新鲜度排序，
 * 断言 {@link #isEnabled()} / {@link #keepCount(int)} 的开关语义、禁用时短路返回空集、
 * 以及实际 Top-N 截断（含跳过 null 候选）。不依赖 DB / Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class PreRankFilterTest {

    @Mock
    private PreRankModel preRankModel;

    private PreRankFilter filter(double keepRatio) {
        RankingProperties props = new RankingProperties();
        props.setPreRankKeepRatio(keepRatio);
        return new PreRankFilter(preRankModel, props);
    }

    /** 让模型以「recency 毫秒」为确定性得分（仅需要实际打分的测试调用）。 */
    private void stubScoreByRecency() {
        when(preRankModel.score(any(PreRankFeatures.class)))
                .thenAnswer(inv -> ((PreRankFeatures) inv.getArgument(0)).recencyMillis());
    }

    private static PreRankCandidate cand(String postId, long epochMillis) {
        FeedItemView item = new FeedItemView(postId, null, null, null, "APPROVED",
                Instant.ofEpochMilli(epochMillis), null, null, null);
        return new PreRankCandidate(item, 0d);
    }

    @Test
    void isEnabled_and_keepCount() {
        PreRankFilter f = filter(0.3d);
        assertTrue(f.isEnabled());
        assertEquals(3, f.keepCount(10));   // floor(10 * 0.3) = 3
        assertEquals(0, f.keepCount(0));
    }

    @Test
    void isEnabled_falseWhenRatioZero() {
        PreRankFilter f = filter(0.0d);
        assertFalse(f.isEnabled());
        assertEquals(0, f.keepCount(10));
    }

    @Test
    void preRank_disabled_returnsEmptySet() {
        PreRankFilter f = filter(0.0d);
        List<PreRankCandidate> cands = List.of(cand("a", 100), cand("b", 200));
        assertTrue(f.preRank(cands, new PreRankContext(null, null, null, null), 3).isEmpty());
    }

    @Test
    void preRank_nullOrEmptyCandidates_returnsEmptySet() {
        PreRankFilter f = filter(0.3d);
        assertTrue(f.preRank(null, new PreRankContext(null, null, null, null), 3).isEmpty());
        assertTrue(f.preRank(List.of(), new PreRankContext(null, null, null, null), 3).isEmpty());
    }

    @Test
    void preRank_keepsTopN_byScore_skipsNullItem() {
        PreRankFilter f = filter(0.3d);
        stubScoreByRecency();
        // 得分 = recency：a=100 b=300 c=200 d=500 e=400；排序后 d,e,b,c,a；keep=3 → {d,e,b}
        List<PreRankCandidate> cands = List.of(
                cand("a", 100),
                cand("b", 300),
                cand("c", 200),
                cand("d", 500),
                cand("e", 400),
                new PreRankCandidate(null, 0d) // item=null → 跳过
        );
        Set<String> kept = f.preRank(cands, new PreRankContext(null, null, null, null), 3);
        assertEquals(Set.of("d", "e", "b"), kept);
    }

    @Test
    void preRank_keepCountLargerThanCandidates_keepsAll() {
        PreRankFilter f = filter(0.3d);
        stubScoreByRecency();
        List<PreRankCandidate> cands = List.of(cand("a", 100), cand("b", 300));
        // keep=10 > 候选数 → 全部保留（且按分数降序：b,a）
        Set<String> kept = f.preRank(cands, new PreRankContext(null, null, null, null), 10);
        assertEquals(Set.of("a", "b"), kept);
    }
}
