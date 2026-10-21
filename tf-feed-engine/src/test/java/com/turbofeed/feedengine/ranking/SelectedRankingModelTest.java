package com.turbofeed.feedengine.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * {@link SelectedRankingModel} 隔离单测（G3/G4 精排模型选择器）。
 *
 * <p>按 {@link RankingProperties#getModelTypeEnum()} 把打分委派给对应实现，使「换模型」退化为改配置。
 * mock 三个具体 {@link RankingModel} 实现，断言每个 modelType（含非法值回落 LINEAR）都委派到正确实现。</p>
 */
@ExtendWith(MockitoExtension.class)
class SelectedRankingModelTest {

    @Mock
    private LinearWeightedRankingModel linear;

    @Mock
    private DeepFmRankingModel deepfm;

    @Mock
    private MultiTargetRankingModel multi;

    private final RankingProperties props = new RankingProperties();

    private final RankingFeatures anyFeatures =
            new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);

    private SelectedRankingModel selector() {
        return new SelectedRankingModel(props, linear, deepfm, multi);
    }

    @Test
    void score_defaultLinearityDelegatesToLinear() {
        when(linear.score(anyFeatures)).thenReturn(1.0d);
        // 默认 modelType=LINEAR
        assertEquals(1.0d, selector().score(anyFeatures), 1e-9);
    }

    @Test
    void score_deepfmDelegatesToDeepFm() {
        props.setModelType("DEEPFM");
        when(deepfm.score(anyFeatures)).thenReturn(2.0d);
        assertEquals(2.0d, selector().score(anyFeatures), 1e-9);
    }

    @Test
    void score_multiTargetDelegatesToMultiTarget() {
        props.setModelType("MULTI_TARGET");
        when(multi.score(anyFeatures)).thenReturn(3.0d);
        assertEquals(3.0d, selector().score(anyFeatures), 1e-9);
    }

    @Test
    void score_invalidModelTypeFallsBackToLinear() {
        props.setModelType("BOGUS_VALUE"); // 非法 → RankModelType.parse 回落 LINEAR
        when(linear.score(anyFeatures)).thenReturn(1.0d);
        assertEquals(1.0d, selector().score(anyFeatures), 1e-9);
    }

    @Test
    void score_nullModelTypeFallsBackToLinear() {
        props.setModelType(null);
        when(linear.score(anyFeatures)).thenReturn(1.0d);
        assertEquals(1.0d, selector().score(anyFeatures), 1e-9);
    }
}
