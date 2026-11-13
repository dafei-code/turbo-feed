package com.turbofeed.gateway.service.moderation;

/**
 * 语义层打分器（L2）：兜规则层漏放的字面变体（改写/谐音/同义）。
 *
 * <p>规则层只认字面；语义层用 embedding 近邻相似度判断「意思是否接近已知违规样本」。
 * 实现是 {@link HttpSemanticScorer}，经 sidecar 完成 embedding + 近邻检索。</p>
 *
 * <p><b>fail-open 契约</b>：实现不得抛异常。模型不可用 / 超时 / 非 2xx 时，返回
 * {@code available=false} 的 {@link SemanticScore#unavailable()}，由决策层放行。
 * 规则层仍 fail-closed，语义层永不成为写路径单点。</p>
 */
public interface ContentScorer {

    /**
     * 对已归一化文本打语义分。
     *
     * @param normalizedText 归一化后的文本（与 AC 喂入同一份）
     * @param userId         提交者（用户级排除/加权预留）
     * @return 语义分；不可用时返回 available=false
     */
    SemanticScore score(String normalizedText, long userId);

    /**
     * 语义分结果。
     *
     * @param score        0~1，越接近 1 越像已知违规内容
     * @param nearestWord  最近的坏样本词（供审计/日志）
     * @param category     最近样本分类
     * @param modelVersion 模型版本（指标/排障）
     * @param latencyMs    本次耗时
     * @param available    false = 模型不可用，决策层应忽略本结果并放行
     */
    record SemanticScore(double score, String nearestWord, String category,
                         String modelVersion, long latencyMs, boolean available) {

        /** 模型不可用的占位结果（fail-open）。 */
        public static SemanticScore unavailable() {
            return new SemanticScore(0.0, null, null, null, 0L, false);
        }
    }
}
