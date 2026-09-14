package com.turbofeed.gateway.client;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.model.FeedItemView;

import java.util.List;

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
 * postId / mediaId / url / images / status / createdAt / caption / captionMark。</p>
 *
 * <p><b>「一帖多图」的映射注意点</b>：{@link MediaItem} 多一个仅网关内部使用的 {@code seq}
 * （帖内序号），契约层不暴露它——引擎与前端只消费<b>有序的</b> {@code images}，
 * 序号是排序手段而不是对外语义，暴露出去反而会让下游产生「按下标复原顺序」的重复逻辑。
 * 反向映射（{@link #toView}）因此把 {@code seq} 固定为 0（帖子视图的代表行序号）。</p>
 */
public final class FeedItemMapper {

    private FeedItemMapper() {
    }

    /**
     * 网关视图 → 引擎契约。状态以枚举名（字符串）传递，引擎侧只认 APPROVED。
     *
     * <p>{@code images} 为空时回退为「单图帖」{@code [url]}：历史单图数据与新建帖子在契约层
     * 必须是同一种形状，否则引擎与前端都要为「老数据」写分支。</p>
     */
    public static FeedItemView toContract(MediaItem item) {
        if (item == null) {
            return null;
        }
        return new FeedItemView(
                item.postId(),
                item.mediaId(),
                item.url(),
                normalizeImages(item),
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
     *
     * <p>读取历史成员串（无 {@code postId} / {@code images}）时，分别回退为
     * 「用 mediaId 当帖身份」与「单图帖」，与仓储层的口径一致，保证「我的内容」与
     * 「公域发现流」不会把同一帖算成两帖。</p>
     */
    public static MediaItem toView(FeedItemView item) {
        if (item == null) {
            return null;
        }
        return new MediaItem(
                item.postId(),
                item.mediaId(),
                item.url(),
                item.imageUrls(),
                0,
                parseStatus(item.status()),
                item.createdAt(),
                item.caption(),
                item.captionMark());
    }

    /** 保证 {@code images} 非空：缺省时回退为单元素列表（{@code url} 为 null 时为空列表）。 */
    private static List<String> normalizeImages(MediaItem item) {
        List<String> images = item.images();
        if (images != null && !images.isEmpty()) {
            return images;
        }
        return item.url() == null ? List.of() : List.of(item.url());
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
