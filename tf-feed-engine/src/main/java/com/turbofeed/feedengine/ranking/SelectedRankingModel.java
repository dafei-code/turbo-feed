package com.turbofeed.feedengine.ranking;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 精排模型选择器（{@link RankingModel} 的 {@code @Primary} 实现）。
 *
 * <p><b>为什么需要这一层</b>：{@code FeedTimelineStore} 只认 {@link RankingModel} 接口，
 * 不知道具体是线性 / DeepFM / 多目标。本类按 {@link RankingProperties#getModelTypeEnum()} 把打分
 * 委派给对应实现，使「换模型」退化为「改一个配置项」——读路径主流程完全不动。</p>
 *
 * <p><b>@Primary 的关键作用</b>：引擎里现在有 4 个 {@link RankingModel} 实现
 * （线性 / DeepFM / 多目标 / 本选择器），{@code FeedTimelineStore} 注入 {@code RankingModel} 时
 * 由 {@code @Primary} 解析到本类，再由本类二次分发。<b>默认 {@code modelType=LINEAR}
 * → 与改造前零回归</b>。</p>
 */
@Primary
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
