package com.turbofeed.feedengine.ranking;

import org.springframework.stereotype.Component;

/**
 * 线性加权多目标精排（{@link RankingModel} 的默认实现）。
 *
 * <p><b>这是"模型化"的起点而非终点</b>：目标与非目标在这里有明确边界——
 * 它能解决的是<b>信号工程</b>（多目标、置信度平滑、量纲统一），
 * 它<b>不能</b>解决的是特征交叉与非线性（那需要双塔 / DeepFM 及离线训练，
 * 属下一阶段；本接口正是为那一步预留的替换插槽）。</p>
 *
 * <h3>一、为什么不直接用原始比率</h3>
 * <p>朴素算法 {@code 完播率 = playCompletes / impressions} 有个致命问题：
 * <b>1 次曝光 1 次完播就是 100%，98% 完播、万次曝光的老内容也是 98%</b>，
 * 于是刚上线的新内容会凭借一个噪声样本压过久经检验的优质内容。本质是<b>把样本量
 * 信息算没了</b>。这里用<b>贝叶斯平滑</b>（虚拟曝光）修：</p>
 * <pre>
 *   smoothed = (successes + priorCount × priorRate) / (trials + priorCount)
 * </pre>
 * 直觉是"先给每条内容垫 {@code priorCount} 次虚拟曝光，其表现按先验水平"：
 * 样本极少时结果贴近先验（不轻易给高分），样本足够后被真实数据主导（不埋没优质）。
 * 这也是为什么特征是<b>原始计数</b>而不是预先算好的比率——比率一旦算出就不可逆。</p>
 *
 * <h3>二、为什么要加"互动率"这个新目标</h3>
 * <p>改造前只有"完播率 + 兴趣匹配"。但完播是弱信号（划过去也 counted），
 * 点赞/评论/分享才是用户的<b>主动</b>表态，抖音意义上的"优质"主要看这个。
 * 多目标各自独立加权、并存于同一公式：谁更重要由配置决定，而不是写死在代码里。</p>
 *
 * <h3>三、量纲统一</h3>
 * <p>recency 是 epoch 毫秒（~10^12），比率是 {@code [0,1]}。两者不能直接相加，
 * 故把每个比率目标乘一个 {@code recencyWindowMillis}（默认 1 小时），
 * 语义变成"相当于内容<b>新了一个小时</b>"，从而与 recency 同量纲、直观可调。</p>
 *
 * <p><b>⚠️ 必须注册为 Spring Bean</b>（{@code @Component}）：{@code FeedTimelineStore}
 * 通过构造器注入 {@link RankingModel}。0055 引入本类时漏了注解，导致
 * <b>引擎启动直接失败</b>（{@code No qualifying bean of type RankingModel}），
 * 而 {@code target/verify} 下的验证脚本是手工 {@code new LinearWeightedRankingModel(...)}，
 * 绕过了 Spring 容器 —— 于是"脚本全绿、服务起不来"。
 * <b>教训：绕过容器的单元级验证不能替代一次真实启动。</b></p>
 */
@Component
public class LinearWeightedRankingModel implements RankingModel {

    private final RankingProperties props;

    public LinearWeightedRankingModel(RankingProperties props) {
        this.props = props;
    }

    @Override
    public double score(RankingFeatures f) {
        double completion = smooth(f.playCompletes(), f.impressions(),
                props.getCompletionPriorCount(), props.getCompletionPriorRate());
        long interactions = f.likes() + f.comments() + f.shares();
        double interaction = smooth(interactions, f.impressions(),
                props.getInteractionPriorCount(), props.getInteractionPriorRate());
        // 长期 + 短期 + session 三重兴趣信号：各自先封顶（防止单标签刷屏把权重推到离谱），再叠加。
        // 短期层 / session 关闭 → 对应分恒为 0 → 退化为纯长期，与改造前语义一致。
        double longInterest = Math.min(f.interestMatch(), props.getInterestScoreCap());
        double shortInterest = Math.min(f.shortTermMatch(), props.getShortTermScoreCap());
        double sessionInterest = Math.min(f.sessionMatch(), props.getSessionScoreCap());
        double interest = longInterest + props.getShortTermWeight() * shortInterest
                + props.getSessionWeight() * sessionInterest;
        double window = props.getRecencyWindowMillis();
        return f.recencyMillis()
                + window * (props.getCompletionWeight() * completion
                          + props.getInteractionWeight() * interaction
                          + props.getInterestWeight() * interest)
                - (f.negativeHit() ? props.getNegativePenaltyMillis() : 0.0d);
    }

    /**
     * 贝叶斯平滑：先给 {@code priorCount} 次虚拟曝光（其中 {@code priorRate} 比例成功），
     * 再叠加真实观测。{@code priorCount = 0} 时退化为朴素比率（便于对照与回滚验证）。
     */
    public double smooth(long successes, long trials, double priorCount, double priorRate) {
        if (priorCount <= 0.0d) {
            return trials <= 0 ? 0.0d : (double) successes / trials;
        }
        return (successes + priorCount * priorRate) / (trials + priorCount);
    }
}
