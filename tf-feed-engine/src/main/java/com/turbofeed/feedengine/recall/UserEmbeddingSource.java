package com.turbofeed.feedengine.recall;

/**
 * 用户向量来源（抖音式双塔的「用户塔」接入缝）。
 *
 * <p><b>为什么抽接口</b>：向量召回要走向"模型化"，第一件事是把"用户向量从哪来"开槽。
 * 当前默认实现 {@link InterestUserEmbeddingSource} 用兴趣画像（长期+短期）经
 * {@link TagEmbeddingService} 编码——零外部依赖、可立即跑通。上线时换成一个
 * "调双塔模型 / 读用户向量KV"的实现即可，召回通道与排序都无需改动。</p>
 *
 * <p>契约：{@code null}/无画像 → 返回 {@code null}（调用方退化为规则召回，冷启动不报错）。</p>
 */
public interface UserEmbeddingSource {

    /**
     * 取用户 embedding（与 {@link ItemEmbeddingSource} 产出的内容向量处于同一空间）。
     *
     * @param userId 用户（{@code null} → {@code null}，匿名/冷启动）
     * @return 归一化向量；无可用信号 → {@code null}
     */
    double[] embeddingOf(String userId);
}
