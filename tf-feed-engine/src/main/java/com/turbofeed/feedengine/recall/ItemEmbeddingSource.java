package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;

/**
 * 内容向量来源（抖音式双塔的「内容塔」接入缝）。
 *
 * <p><b>为什么抽接口</b>：与 {@link UserEmbeddingSource} 对称。默认实现
 * {@link TagItemEmbeddingSource} 用内容标签经 {@link TagEmbeddingService} 编码。
 * 上线时换成"离线算好、写 Redis KV {@code tf:emb:item:{timelineKey}}"的读取实现，
 * 或"调双塔内容塔推理"的实现——接口签名不变，召回通道无感。</p>
 *
 * <p>契约：无标签/异常 → 返回 {@code null}（该内容不参与向量召回）。</p>
 */
public interface ItemEmbeddingSource {

    /**
     * 取内容 embedding（与 {@link UserEmbeddingSource} 产出的用户向量处于同一空间）。
     *
     * @param item 候选内容
     * @return 归一化向量；无可用信号 → {@code null}
     */
    double[] embeddingOf(FeedItemView item);
}
