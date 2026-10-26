package com.turbofeed.shared.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 跨服务契约的版本协商（M2 / 4.1）。
 *
 * <p><b>它解决什么</b>：tf-shared 里的 record 是网关 ⇄ 引擎 ⇄ 计数器之间的协议。没有版本号时，
 * 兼容完全靠「未知字段忽略 + 可空字段回退」这套默契——这对<b>加可空字段</b>是够的，但对
 * 「字段改名 / 语义翻转 / 删字段」这类<b>不兼容变更</b>是不够的：旧代码会读到一堆 {@code null}，
 * 然后按回退逻辑走出一个「看起来正常、实际内容错了」的结果，且没有任何报错。
 * 版本号把这种情况从「静默出错」变成「明确拒绝」。</p>
 *
 * <p><b>协商规则（只有一条硬边界）</b>：</p>
 * <ul>
 *   <li><b>缺失 / 非正值</b> → 容忍。本字段引入<b>之前</b>产生的存量数据必然没有版本号——
 *       MQ 里未被消费的旧消息、Redis 时间线里已物化的成员串，都不可能回溯补写。
 *       把它们一律拒掉等于上线即事故，因此缺失按「首版语义」处理；</li>
 *   <li><b>不高于当前版本</b> → 容忍。向下兼容是硬约束：新增字段可空，旧版本消息按缺失处理；</li>
 *   <li><b>高于当前版本</b> → <b>拒绝</b>，抛 {@link UnsupportedContractVersionException}。
 *       这意味着生产者已升级、消费者还没升——继续解析必然按错误语义处理。</li>
 * </ul>
 *
 * <p><b>版本号怎么用（升级规程）</b>：只有<b>不兼容</b>变更才递增版本号；加可空字段不必递增
 * （未知字段默认忽略，正反两个方向都安全）。递增时的发布顺序是先升消费者、再升生产者，
 * 否则中间窗口里新消息会被旧消费者拒绝——这个顺序依赖 R8 之外没有别的机制保证，
 * 必须写进发布手册，见 {@code docs/architecture/contract-evolution.md}。</p>
 *
 * <p><b>零依赖定位不变</b>：本类只用 JDK，与 tf-shared 的「零第三方依赖」一致
 * （由 {@code ArchitectureTest} 强制）。</p>
 */
public final class ContractVersion {

    private ContractVersion() {
    }

    /**
     * 校验一条契约数据能否被当前代码安全地解释。
     *
     * @param contract  契约名（仅用于报错与日志定位，如 {@code "FeedTimelineEvent"}）
     * @param supported 当前代码能解释的最高版本（取该契约自身的 {@code SCHEMA_VERSION} 常量）
     * @param seen      数据里携带的版本；{@code null} 表示存量数据（本字段引入前产生）
     * @throws UnsupportedContractVersionException 版本高于 {@code supported} 时抛出
     */
    public static void requireConsumable(String contract, int supported, Integer seen) {
        if (seen == null || seen <= 0) {
            return; // 存量数据：按首版语义容忍，见类注释
        }
        if (seen <= supported) {
            return;
        }
        throw new UnsupportedContractVersionException(contract, seen, supported);
    }

    /**
     * 该版本是否可被当前代码消费（不抛异常的判定版，供读路径 fail-open 使用）。
     *
     * <p><b>为什么需要非抛异常的形式</b>：读路径（时间线翻页）单条成员串解析失败应当
     * <b>跳过这一条</b>而不是让整页失败。用异常做控制流既慢又难读，因此给一个纯粹的判定方法。</p>
     */
    public static boolean isConsumable(int supported, Integer seen) {
        return seen == null || seen <= 0 || seen <= supported;
    }

    /**
     * 批量版（M3 契约旁路收口）：剔除一批契约数据里当前代码无法安全解释的条目。
     *
     * <p><b>为什么需要批量版</b>：单条解析（{@code readValue(..., Xxx.class)}）可以在解析后逐条判定，
     * 但<b>集合解析</b>是一条 JSON 数组一次反序列化出来的（网关解析引擎返回的推荐流、引擎读本地
     * 推荐流缓存都是这种形态）。此时没有「逐条 try/catch」的时机，只能在解析完成后按版本号过滤。
     * 若每个调用方各写一遍过滤，就会出现「这边过滤了、那边没过滤」的静默分歧——与
     * {@link #isConsumable(int, Integer)} 分处两地的老问题同构。因此把过滤收敛到这里。</p>
     *
     * <p><b>为什么取版本号用回调</b>：本类在 tf-shared，必须保持<b>零第三方依赖</b>，
     * 而各契约 record 的版本字段名统一但所属类型各异。用 {@code Function} 取值既能共享过滤逻辑，
     * 又不让 tf-shared 反向依赖具体契约类。</p>
     *
     * <p><b>为什么回调返回 {@code Integer} 而不是 {@code int}</b>：版本号<b>本来就可空</b>
     * （存量数据没有这个字段）。若用 {@code ToIntFunction}，方法引用 {@code FeedItemView::schemaVersion}
     * 会在每一条存量数据上触发<b>隐式拆箱 NPE</b>——一个只在老数据上炸、单测用新数据全绿的隐蔽坑。
     * 保持包装类型，让「可空」这件事在签名上就可见。</p>
     *
     * @param items         待过滤的一批契约数据；{@code null} 视为空
     * @param supported     当前代码能解释的最高版本
     * @param versionGetter 从一条数据上取版本号的函数（如 {@code FeedItemView::schemaVersion}）
     * @param <T>           契约数据类型
     * @return 只含可消费条目的新列表；入参为空或全被剔除时返回不可变空列表
     */
    public static <T> List<T> filterConsumable(List<T> items, int supported, Function<T, Integer> versionGetter) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(items.size());
        for (T item : items) {
            if (item != null && isConsumable(supported, versionGetter.apply(item))) {
                out.add(item);
            }
        }
        return out;
    }
}
