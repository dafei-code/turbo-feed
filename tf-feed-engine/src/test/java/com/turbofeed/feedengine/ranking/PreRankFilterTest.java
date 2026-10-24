package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link PreRankFilter} 隔离单测（G1 粗排截断层）。
 *
 * <p>纯逻辑：mock {@link PreRankModel}，用「得分 = recency 毫秒」的确定性 Answer 让候选按新鲜度排序，
 * 断言开关语义、逐池启用阈值、保留条数下限（防塌缩）、以及<b>有序</b>的 Top-N 结果。
 * 不依赖 DB / Redis。</p>
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

    private PreRankFilter filter(double keepRatio, int minCandidates, int minPages) {
        RankingProperties props = new RankingProperties();
        props.setPreRankKeepRatio(keepRatio);
        props.setPreRankMinCandidates(minCandidates);
        props.setPreRankMinPages(minPages);
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

    private static PreRankContext emptyCtx() {
        return new PreRankContext(null, null, null, null);
    }

    @Test
    void isEnabled_followsRatio() {
        assertTrue(filter(0.3d).isEnabled());
        assertFalse(filter(0.0d).isEnabled());
    }

    @Test
    void isEnabledFor_skipsPoolsBelowMinCandidates() {
        PreRankFilter f = filter(0.3d, 50, 5);
        assertFalse(f.isEnabledFor(49));    // 池太小 → 跳过（小数据零变化）
        assertTrue(f.isEnabledFor(50));
        assertTrue(f.isEnabledFor(1500));
        // 全局关闭时无论池多大都不启用
        assertFalse(filter(0.0d, 50, 5).isEnabledFor(1500));
    }

    @Test
    void keepCount_floorByMinPages_preventsPageCollapse() {
        PreRankFilter f = filter(0.3d, 50, 5);
        // 池只有 20 条：按比例仅 ceil(20×0.3)=6 条，但下限 20×5=100 兜底
        // （调用方再 min 到池大小），保证该池能填满自己分摊到的前若干页。
        assertEquals(100, f.keepCount(20, 20));
        // 大池按比例：ceil(1000 × 0.3) = 300 > 下限 → 300
        assertEquals(300, f.keepCount(20, 1000));
        // 下限随 minPages 变化：minPages=2 → 下限 40，压过比例的 30
        assertEquals(40, filter(0.3d, 50, 2).keepCount(20, 100));
    }

    @Test
    void preRank_disabled_returnsEmptyList() {
        PreRankFilter f = filter(0.0d);
        List<PreRankCandidate> cands = List.of(cand("a", 100), cand("b", 200));
        assertTrue(f.preRank(cands, emptyCtx(), 3).isEmpty());
    }

    @Test
    void preRank_nullOrEmptyCandidates_returnsEmptyList() {
        PreRankFilter f = filter(0.3d);
        assertTrue(f.preRank(null, emptyCtx(), 3).isEmpty());
        assertTrue(f.preRank(List.of(), emptyCtx(), 3).isEmpty());
        // keep<=0 亦为空（语义 = 不裁剪）
        assertTrue(f.preRank(List.of(cand("a", 100)), emptyCtx(), 0).isEmpty());
    }

    @Test
    void preRank_returnsKeysOrderedByScoreDesc() {
        PreRankFilter f = filter(0.3d);
        stubScoreByRecency();
        // 得分 = recency：a=100 b=300 c=200 d=500 e=400；降序 d,e,b,c,a
        List<PreRankCandidate> cands = List.of(
                cand("a", 100),
                cand("b", 300),
                cand("c", 200),
                cand("d", 500),
                cand("e", 400),
                new PreRankCandidate(null, 0d) // item=null → 跳过
        );
        // 顺序本身即语义：调用方按返回顺序重排池内候选 + 截断，故必须严格降序
        assertEquals(List.of("d", "e", "b"), f.preRank(cands, emptyCtx(), 3));
        assertEquals(List.of("d", "e", "b", "c", "a"), f.preRank(cands, emptyCtx(), 5));
    }

    @Test
    void preRank_keepLargerThanCandidates_keepsAllOrdered() {
        PreRankFilter f = filter(0.3d);
        stubScoreByRecency();
        List<PreRankCandidate> cands = List.of(cand("a", 100), cand("b", 300));
        // keep=10 > 候选数 → 全部保留，且按分数降序（b 先于 a）
        assertEquals(List.of("b", "a"), f.preRank(cands, emptyCtx(), 10));
    }
}
