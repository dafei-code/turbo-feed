package com.turbofeed.feedengine.ranking;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 精排模型选择器（{@link RankingModel} 实现，被 {@code InstrumentedRankingModel} 包为 {@code @Primary}）。
 *
 * <p><b>为什么需要这一层</b>：{@code FeedTimelineStore} 只认 {@link RankingModel} 接口，
 * 不知道具体是线性 / DeepFM / 多目标。本类按 {@link RankingProperties#getModelTypeEnum()} 把打分
 * 委派给对应实现，使「换模型」退化为「改一个配置项」——读路径主流程完全不动。</p>
 *
 * <p><b>@Primary 已迁至装饰器</b>：fail-open 可观测性（见 {@code InstrumentedRankingModel}）需要在
 * {@code FeedTimelineStore} 注入的 {@code RankingModel} 上做无感计时/计数。为此 {@code @Primary}
 * 移到 {@code InstrumentedRankingModel}，本类保留为被装饰的委托实现，二次分发逻辑不变。
 * <b>默认 {@code modelType=LINEAR} → 与改造前零回归</b>。</p>
 */
@Component
public class SelectedRankingModel implements RankingModel {

    private final RankingProperties props;
    private final LinearWeightedRankingModel linear;
    private final DeepFmRankingModel deepfm;
    private final MultiTargetRankingModel multi;

    public SelectedRankingModel(RankingProperties props,
                                LinearWeightedRankingModel linear,
                                DeepFmRankingModel deepfm,
                                MultiTargetRankingModel multi) {
        this.props = props;
        this.linear = linear;
        this.deepfm = deepfm;
        this.multi = multi;
    }

    @Override
    public double score(RankingFeatures features) {
        switch (props.getModelTypeEnum()) {
            case DEEPFM:
                return deepfm.score(features);
            case MULTI_TARGET:
                return multi.score(features);
            case LINEAR:
            default:
                return linear.score(features);
        }
    }
}
