package com.turbofeed.gateway.client;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.model.FeedItemView;

/**
 * 网关视图 ⇄ 引擎契约的双向映射。
 *
 * <p><b>为什么要这一层</b>：{@link MediaItem} 是网关对外 API 的响应模型（含审核状态枚举等
 * 网关内部概念），{@link FeedItemView} 是跨服务契约。两者当前字段一一对应，但<b>不能因此
 * 合并</b>——契约一旦被网关的内部演进牵着走（比如给 MediaItem 加字段、改状态机），
 * 所有下游服务都会被动跟着改。保持一个显式映射层，让"对外 API 变了"和"服务间契约变了"
 * 成为两件可独立决策的事。</p>
 *
 * <p>字段对应关系（JSON 层次完全一致，故历史 Redis 数据无需迁移）：
 * mediaId / url / status / createdAt / caption / captionMark。</p>
 */
public final class FeedItemMapper {

    private FeedItemMapper() {
    }

    /** 网关视图 → 引擎契约。状态以枚举名（字符串）传递，引擎侧只认 APPROVED。 */
    public static FeedItemView toContract(MediaItem item) {
        if (item == null) {
            return null;
        }
        return new FeedItemView(
                item.mediaId(),
                item.url(),
                item.status() == null ? null : item.status().name(),
                item.createdAt(),
                item.caption(),
                item.captionMark());
    }

    /**
     * 引擎契约 → 网关视图。
     *
     * <p>状态字符串无法识别时按 {@link MediaStatus#PENDING} 处理（不抛异常）：契约层出现未知
     * 状态说明两侧版本不一致，此时"当作处理中"比让整条发现流 500 更合理——单个条目的字段
     * 异常不应放大成接口级故障。</p>
     */
    public static MediaItem toView(FeedItemView item) {
        if (item == null) {
            return null;
        }
        return new MediaItem(
                item.mediaId(),
                item.url(),
                parseStatus(item.status()),
                item.createdAt(),
                item.caption(),
                item.captionMark());
    }

    private static MediaStatus parseStatus(String raw) {
        if (raw == null) {
            return MediaStatus.PENDING;
        }
        try {
            return MediaStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return MediaStatus.PENDING;
        }
    }
}
