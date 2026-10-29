package com.turbofeed.feedengine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.ContractVersion;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.RecommendedItem;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FeedItemView} 的<b>唯一</b>反序列化入口（M3 契约旁路收口）。
 *
 * <p><b>它解决什么</b>：M2/4.1 给契约加了版本协商，但当时只挂在 {@code TimelineSupport#parse} 上——
 * 而 {@code FeedItemView} 的解析点散在 {@code timeline} / {@code recall} 两个包共 5 处，
 * 其中 4 处是<b>裸 {@code readValue}</b>（另 1 处是集合解析），完全不走协商。也就是说：
 * 只要生产者先升级，这些路径就会<b>按错误语义静默处理数据</b>，而版本协商形同虚设。
 * 「有协商」和「协商覆盖全部路径」是两件事，本类补上后者。</p>
 *
 * <p><b>为什么是新包而不是放进 {@code timeline}</b>：{@code TimelineSupport} 是
 * {@code timeline} 包的<b>包可见</b>工具类（刻意不外泄）。把 {@code recall} 包也用到的解析
 * 塞进去，等于要么提升它的可见性、要么在 {@code recall} 里复制一份——前者让时间线的内部
 * 常量对外暴露，后者重新制造「两份真值」。因此解析上提到 {@code contract} 包：它只依赖
 * tf-shared 的契约与 Jackson，任何包都可以用，且不牵出任何一方的内部实现。</p>
 *
 * <p><b>读路径口径（与写路径相对）</b>：任何异常 / 版本不兼容 → <b>跳过这一条</b>，返回
 * {@code null}（单条）或不包含该条（集合），<b>绝不抛出去</b>。读路径宁可少一条内容，
 * 也不能让一整页 Feed 因为一个坏成员串失败。写路径的「拒绝并留痕进 DLQ」在
 * {@code FeedTimelineDeliveryService}，与本类无关。</p>
 */
public final class ItemViewCodec {

    private ItemViewCodec() {
    }

    /**
     * 反序列化单条成员串（时间线 ZSET 成员、向量索引 payload、标签共现样本等形态）。
     *
     * @param objectMapper 调用方注入的 mapper（本类不持有状态）
     * @param json         成员串；{@code null} 返回 {@code null}
     * @return 可消费的条目；解析失败或版本不兼容返回 {@code null}
     */
    public static FeedItemView parse(ObjectMapper objectMapper, String json) {
        if (json == null) {
            return null;
        }
        FeedItemView item;
        try {
            item = objectMapper.readValue(json, FeedItemView.class);
        } catch (Exception ignore) {
            // 成员串可能是历史格式，也可能被写坏：读路径跳过这一条，不让整页失败
            return null;
        }
        if (!ContractVersion.isConsumable(FeedItemView.SCHEMA_VERSION,
                item == null ? null : item.schemaVersion())) {
            return null;
        }
        return item;
    }

    /**
     * 反序列化「一整个 JSON 数组」形态的条目（推荐流缓存等），并剔除版本不可消费的条目。
     *
     * <p><b>为什么集合版只能事后过滤</b>：数组是一次性反序列化出来的，没有逐条 try/catch 的
     * 时机；好在版本不兼容不会让 Jackson 报错（那只是个偏大的数字），因此先整体解析、
     * 再按版本号过滤，效果等价于逐条跳过。</p>
     *
     * @param objectMapper 调用方注入的 mapper
     * @param json         JSON 数组串；{@code null} 返回空列表
     * @return 只含可消费条目的列表；整体解析失败返回空列表（不抛）
     */
    public static List<FeedItemView> parseList(ObjectMapper objectMapper, String json) {
        if (json == null) {
            return List.of();
        }
        List<FeedItemView> items;
        try {
            items = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FeedItemView.class));
        } catch (Exception ignore) {
            return List.of();
        }
        return ContractVersion.filterConsumable(items, FeedItemView.SCHEMA_VERSION, FeedItemView::schemaVersion);
    }

    /**
     * 反序列化「一整个 JSON 数组」形态的<b>带召回归因的条目</b>（{@link RecommendedItem}，推荐流缓存用），
     * 并剔除版本不可消费的条目（按内嵌 {@link FeedItemView} 的 schemaVersion 判定）。
     *
     * <p><b>为什么单独一个方法</b>：{@link #parseList} 服务于<b>裸条目</b>缓存（时间线冷启动候选等），
     * 本方法服务于<b>带归因条目</b>缓存（推荐流 {@code tf:feed:rec:*}）。二者缓存 Key 作用域、
     * TTL 完全一致，但条目形状不同——归因只存在于推荐流响应，不能污染时间线读路径。</p>
     *
     * @param objectMapper 调用方注入的 mapper
     * @param json         JSON 数组串；{@code null} 返回空列表
     * @return 只含可消费条目的列表；整体解析失败返回空列表（不抛）
     */
    public static List<RecommendedItem> parseListRecommended(ObjectMapper objectMapper, String json) {
        if (json == null) {
            return List.of();
        }
        List<RecommendedItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            for (JsonNode node : root) {
                if (node == null || node.isNull()) {
                    continue;
                }
                if (node.has("item") && !node.get("item").isNull()) {
                    // 新格式（#307 起）：{item:{...}, recallChannel, poolLevel}
                    items.add(objectMapper.treeToValue(node, RecommendedItem.class));
                } else {
                    // 旧格式（#307 前的裸条目缓存）：整节点即 FeedItemView。
                    // 滚动发布期间旧缓存仍会被读到，必须按「无归因」降级而非整页丢弃——
                    // 否则一次部署会让所有在途缓存页瞬间变空（用户看到推荐流「空了」）。
                    FeedItemView view = objectMapper.treeToValue(node, FeedItemView.class);
                    if (view != null) {
                        items.add(new RecommendedItem(view, null, null));
                    }
                }
            }
        } catch (Exception ignore) {
            return List.of();
        }
        return ContractVersion.filterConsumable(items, FeedItemView.SCHEMA_VERSION,
                ri -> ri == null || ri.item() == null ? null : ri.item().schemaVersion());
    }
}
