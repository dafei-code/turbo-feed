package com.turbofeed.shared.model;

/**
 * 推荐流单条返回（跨服务契约）：在 {@link FeedItemView} 之外附带<b>本次请求的召回归因</b>，
 * 供训练样本与 A/B 实验按「通道 × 实验桶」做效果对照。
 *
 * <p><b>为什么不复用 {@link FeedItemView} 直接加字段</b>：{@code FeedItemView} 会被序列化进
 * {@code tf:feed:tl:*} 时间线成员串<b>长期驻留 Redis</b>，而 {@code recallChannel}/{@code poolLevel}
 * 是「本次请求、该用户视角」的瞬时归因，不属于条目固有属性——塞进成员串既污染长存数据、又会
 * 无意义地放大每个时间线条目的内存。故独立成 DTO，只在引擎→网关的响应里出现（不进 Redis）。</p>
 *
 * <p>{@code recallChannel} 取值：{@code HOT} / {@code INTEREST} / {@code CF} / {@code VECTOR} /
 * {@code FOLLOW} / {@code POOL} / {@code COLD}；{@code POOL} 时 {@code poolLevel} 有值
 * （1..N 流量池层级），其余通道为 {@code null}。</p>
 *
 * <p><b>JSON 约定</b>：本 DTO 是 record，Jackson 经 {@code RecordComponent} 反射按组件名
 * （{@code item} / {@code recallChannel} / {@code poolLevel}）序列化与反序列化，无需
 * {@code @JsonProperty}（tf-shared 不依赖 jackson-annotations）。命名务必与引擎侧写、网关侧读一致。</p>
 *
 * @param item         内容条目（与既有契约一致）
 * @param recallChannel 产生该条内容的召回通道（见上）
 * @param poolLevel    流量池层级（仅 {@code recallChannel=POOL} 时有值）
 */
public record RecommendedItem(
        FeedItemView item,
        String recallChannel,
        Integer poolLevel) {
}
