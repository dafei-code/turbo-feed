package com.turbofeed.feedengine.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 精排打分的配置（全部带默认值，<b>不强制改 yml</b>）。
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.rank")
public class RankingProperties {

    /** 完播率目标权重（单位：等效"入流时刻窗口"倍数，见 {@link #recencyWindowMillis}）。 */
    private double completionWeight = 0.15d;

    /** 互动率目标权重。抖音式"互动即优质"，互动成本高于完播，默认给更高权重。 */
    private double interactionWeight = 0.20d;

    /** 兴趣匹配目标权重。 */
    private double interestWeight = 1.0d;

    /**
     * 短期兴趣匹配目标权重（抖音式"当下在追什么"对排序的影响强度）。
     *
     * <p>长期层定"你是什么样的人"、短期层定"你现在想要什么"。{@link #interestWeight}
     * 作用于长期匹配分，本项作用于短期匹配分，二者独立可调：调大本项 = 更看重最近行为、
     * 推荐更"跟手"；调小 = 更稳、不被一次性突发兴趣带偏。</p>
     */
    private double shortTermWeight = 1.0d;

    /** 短期兴趣累加分上限（与 {@link #interestScoreCap} 同口径，单独封顶防止某次刷屏把短期权重推到离谱）。 */
    private double shortTermScoreCap = 3.0d;

    /**
     * session 序列匹配目标权重（抖音式"刚看过的再多给"的跟手强度）。
     *
     * <p>与 {@link #shortTermWeight} 互补：短期层是"聚合后的突发偏好"，session 是"未聚合的局部强时效"。
     * 调大本项 = 更强的即时跟随（刚连看 5 条钓鱼，下一条钓鱼立刻顶上来）；调小 = 更稳。
     * session 关闭时该信号恒为 0，不影响其它目标。</p>
     */
    private double sessionWeight = 0.5d;

    /** session 序列匹配分上限（与 {@link #interestScoreCap} 同口径）。 */
    private double sessionScoreCap = 3.0d;

    /**
     * 完播率的先验：相当于先给每条内容虚拟 {@code completionPriorCount} 次曝光，
     * 其中 {@code completionPriorRate} 比例完播。样本越少，结果越贴近这个先验。
     */
    private double completionPriorCount = 20.0d;
    private double completionPriorRate = 0.20d;

    /** 互动率的先验（同完播率口径）。互动天然更稀疏，故先验给得比完播低。 */
    private double interactionPriorCount = 20.0d;
    private double interactionPriorRate = 0.05d;

    /** 兴趣累加分上限（防止少数强互动把某标签权重推到离谱，淹没时间序）。 */
    private double interestScoreCap = 3.0d;

    /** 命中用户负向标签时的惩罚（毫秒；远大于窗口，保证"明确不要"压过"可能喜欢"）。 */
    private double negativePenaltyMillis = 30L * 24 * 3_600_000L;

    /**
     * 实时特征匹配目标权重（抖音式「实时特征流」：最近互动 + 短期 burst 的聚合信号对排序的影响强度）。
     *
     * <p>与 {@link #interestWeight} 同量纲（都折算成"等效入流时刻窗口"倍数），调大 = 更"跟手"、
     * 刚互动过的标签立刻影响排序；调小 = 更稳。</p>
     */
    private double realtimeWeight = 0.5d;

    /** 实时特征匹配分上限（与 {@link #interestScoreCap} 同口径，防某次刷屏把实时权重推到离谱）。 */
    private double realtimeScoreCap = 3.0d;

    /** 各比率目标折算成"等效入流时刻毫秒数"的窗口（默认 1 小时），使 {@code [0,1]} 的比率与 recency 量级可比。 */
    private long recencyWindowMillis = 3_600_000L;

    // ===================== 精排模型族选型（抖音式「精排模型化」G3/G4） =====================
    /** 精排模型选型：LINEAR（默认，零回归）/ DEEPFM / MULTI_TARGET。 */
    private String modelType = "LINEAR";

    /** 是否启用远程精排模型（{@link RankModelClient} 存在时直发远程 serving，fail-open 退回本地 FM）。 */
    private boolean remoteModelEnabled = false;

    // ===================== 粗排（pre-rank）截断层（G1） =====================
    /** 粗排保留倍数（相对单页 limit）：0 = 关闭（零回归），>0 时保留 {@code limit × ratio} 条进精排。 */
    private double preRankKeepRatio = 0.0d;

    // ===================== DeepFM 特征交叉项（G3） =====================
    /** FM 嵌入维度（确定性 hash 初始化，无需训练产物即可跑）。 */
    private int deepfmFieldDim = 8;
    /** 二阶交叉项权重（默认很小：交叉是线性项的「增强」，不为 0 即叠加交叉效应；=0 时 ≡ 线性模型）。 */
    private double deepfmCrossWeight = 0.3d;
    /** 嵌入初始化 seed（确定性，换 seed = 换一组交叉方向，便于离线对照）。 */
    private long deepfmSeed = 1L;

    // ===================== 多目标融合（G4） =====================
    /** 多目标融合：CTR（互动率）权重。 */
    private double mtCtrWeight = 1.0d;
    /** 多目标融合：CVR（完播率）权重。 */
    private double mtCvrWeight = 1.0d;
    /** 多目标融合：dwell（新鲜度代理停留意愿）权重。 */
    private double mtDwellWeight = 0.5d;
    /** 多目标融合：interact（绝对互动强度）权重。 */
    private double mtInteractWeight = 1.0d;

    public double getCompletionWeight() {
        return completionWeight;
    }

    public void setCompletionWeight(double completionWeight) {
        this.completionWeight = completionWeight;
    }

    public double getInteractionWeight() {
        return interactionWeight;
    }

    public void setInteractionWeight(double interactionWeight) {
        this.interactionWeight = interactionWeight;
    }

    public double getInterestWeight() {
        return interestWeight;
    }

    public void setInterestWeight(double interestWeight) {
        this.interestWeight = interestWeight;
    }

    public double getShortTermWeight() {
        return shortTermWeight;
    }

    public void setShortTermWeight(double shortTermWeight) {
        this.shortTermWeight = shortTermWeight;
    }

    public double getShortTermScoreCap() {
        return shortTermScoreCap;
    }

    public void setShortTermScoreCap(double shortTermScoreCap) {
        this.shortTermScoreCap = shortTermScoreCap;
    }

    public double getSessionWeight() {
        return sessionWeight;
    }

    public void setSessionWeight(double sessionWeight) {
        this.sessionWeight = sessionWeight;
    }

    public double getSessionScoreCap() {
        return sessionScoreCap;
    }

    public void setSessionScoreCap(double sessionScoreCap) {
        this.sessionScoreCap = sessionScoreCap;
    }

    public double getCompletionPriorCount() {
        return completionPriorCount;
    }

    public void setCompletionPriorCount(double completionPriorCount) {
        this.completionPriorCount = completionPriorCount;
    }

    public double getCompletionPriorRate() {
        return completionPriorRate;
    }

    public void setCompletionPriorRate(double completionPriorRate) {
        this.completionPriorRate = completionPriorRate;
    }

    public double getInteractionPriorCount() {
        return interactionPriorCount;
    }

    public void setInteractionPriorCount(double interactionPriorCount) {
        this.interactionPriorCount = interactionPriorCount;
    }

    public double getInteractionPriorRate() {
        return interactionPriorRate;
    }

    public void setInteractionPriorRate(double interactionPriorRate) {
        this.interactionPriorRate = interactionPriorRate;
    }

    public double getInterestScoreCap() {
        return interestScoreCap;
    }

    public void setInterestScoreCap(double interestScoreCap) {
        this.interestScoreCap = interestScoreCap;
    }

    public double getNegativePenaltyMillis() {
        return negativePenaltyMillis;
    }

    public void setNegativePenaltyMillis(double negativePenaltyMillis) {
        this.negativePenaltyMillis = negativePenaltyMillis;
    }

    public double getRealtimeWeight() {
        return realtimeWeight;
    }

    public void setRealtimeWeight(double realtimeWeight) {
        this.realtimeWeight = realtimeWeight;
    }

    public double getRealtimeScoreCap() {
        return realtimeScoreCap;
    }

    public void setRealtimeScoreCap(double realtimeScoreCap) {
        this.realtimeScoreCap = realtimeScoreCap;
    }

    public long getRecencyWindowMillis() {
        return recencyWindowMillis;
    }

    public void setRecencyWindowMillis(long recencyWindowMillis) {
        this.recencyWindowMillis = recencyWindowMillis;
    }

    // ===================== 精排模型族 / 粗排 / DeepFM / 多目标：getter-setter + 解析 =====================

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    /** 容错解析：非法值回落 LINEAR（fail-open 不阻断，且与改造前语义一致）。 */
    public RankModelType getModelTypeEnum() {
        return RankModelType.parse(modelType);
    }

    public boolean isRemoteModelEnabled() {
        return remoteModelEnabled;
    }

    public void setRemoteModelEnabled(boolean remoteModelEnabled) {
        this.remoteModelEnabled = remoteModelEnabled;
    }

    public double getPreRankKeepRatio() {
        return preRankKeepRatio;
    }

    public void setPreRankKeepRatio(double preRankKeepRatio) {
        this.preRankKeepRatio = preRankKeepRatio;
    }

    public int getDeepfmFieldDim() {
        return deepfmFieldDim;
    }

    public void setDeepfmFieldDim(int deepfmFieldDim) {
        this.deepfmFieldDim = deepfmFieldDim;
    }

    public double getDeepfmCrossWeight() {
        return deepfmCrossWeight;
    }

    public void setDeepfmCrossWeight(double deepfmCrossWeight) {
        this.deepfmCrossWeight = deepfmCrossWeight;
    }

    public long getDeepfmSeed() {
        return deepfmSeed;
    }

    public void setDeepfmSeed(long deepfmSeed) {
        this.deepfmSeed = deepfmSeed;
    }

    public double getMtCtrWeight() {
        return mtCtrWeight;
    }

    public void setMtCtrWeight(double mtCtrWeight) {
        this.mtCtrWeight = mtCtrWeight;
    }

    public double getMtCvrWeight() {
        return mtCvrWeight;
    }

    public void setMtCvrWeight(double mtCvrWeight) {
        this.mtCvrWeight = mtCvrWeight;
    }

    public double getMtDwellWeight() {
        return mtDwellWeight;
    }

    public void setMtDwellWeight(double mtDwellWeight) {
        this.mtDwellWeight = mtDwellWeight;
    }

    public double getMtInteractWeight() {
        return mtInteractWeight;
    }

    public void setMtInteractWeight(double mtInteractWeight) {
        this.mtInteractWeight = mtInteractWeight;
    }
}
