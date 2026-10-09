package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生态调控层（抖音式「生态调控」G9）。
 *
 * <p><b>对标的是什么</b>：G8 上下文感知重排只在「滑动窗口」内做作者去重 + 标签 Jaccard 惩罚，
 * 是<b>局部</b>多样性；抖音还有一层<b>整页全局</b>的生态调控——防止单一话题 / 单一作者
 * 垄断整页（生态失衡 = 信息茧房 + 头部通吃）。本服务在<b>整页范围</b>上施加全局占比配额，
 * 与 G8 互补（G8=局部窗、G9=整页全局）。</p>
 *
 * <p><b>为什么作用在「整页组装完成后」而非「仅流量池」</b>：早期实现只对 {@code poolPart}
 * 做配额，但 G7 冷启动探索池会把「刚过审的新鲜内容」注入 {@code coldPart}、G6 关注流把关注作者
 * 内容注入 {@code followPart}——这些来源同样能制造「单一作者垄断整页」。只控流量池等于漏掉两路，
 * 单作者仍可通过「冷启动 + 关注」两路在整页刷出超额条数。故 G9 改为对<b>所有来源合并后的整页</b>
 * 做最后一次全局封顶（保序裁低位超额尾部），才是真正落地「整页全局占比配额」。</p>
 *
 * <p><b>调控项（分母 = 整页目标条数 {@code pageLimit}，不是某来源切片大小）</b>：
 * <ul>
 *   <li>单标签全局占比封顶：任一标签在整页内占比超过 {@code maxTagShare} → 裁剪该标签低分尾部；</li>
 *   <li>单作者全局条数封顶：任一作者在整页内超过 {@code maxAuthorShare} 条 → 裁剪低分尾部。</li>
 * </ul>
 * 两者都「保留已排好序的高位内容、裁低位超额尾部」，不随机删，保证被留下的仍是该标签/作者下最优质的内容。</p>
 *
 * <p><b>fail-open</b>：任何异常 → 返回原序（不丢内容、不阻断浏览）。调控层是「体验优化」，
 * 优先级低于「内容可达」。</p>
 */
@Component
public class EcosystemRegulationService {

    private static final Logger log = LoggerFactory.getLogger(EcosystemRegulationService.class);

    private final EcosystemRegulationProperties props;

    public EcosystemRegulationService(EcosystemRegulationProperties props) {
        this.props = props;
    }

    /**
     * 对整页内容做全局生态调控（抖音式「生态调控」G9，整页全局占比配额）。
     *
     * @param page      已按展示顺序组装好的整页内容（热点/关注/兴趣/向量/流量池/冷启动 全部来源合并后）
     * @param pageLimit 整页目标条数（配额占比的分母；与 G8 滑窗重排互补：G8=局部窗、G9=整页全局）
     * @return 调控后内容（同集合、同成员，仅可能移除超额低分尾部；保序）
     */
    public List<FeedItemView> regulate(List<FeedItemView> page, int pageLimit) {
        if (!props.isEnabled() || page == null || page.size() <= 1) {
            return page;
        }
        try {
            return applyCaps(page, pageLimit);
        } catch (Exception e) {
            log.warn("生态调控异常，回退原序（fail-open）: {}", e.getMessage());
            return page;
        }
    }

    private List<FeedItemView> applyCaps(List<FeedItemView> page, int pageLimit) {
        int limit = Math.max(1, pageLimit);
        // 分母用整页目标条数，而非传入切片大小：否则「流量池切片只有 N 条」会让 maxTagShare
        // 被解释成「切片内的占比」，彻底丧失「整页全局」意义（实测曾打出「标签封顶=1」）。
        int tagCap = (int) Math.max(1, Math.ceil(limit * props.getMaxTagShare()));
        int authorCap = Math.max(1, props.getMaxAuthorShare());

        // 统计每张/每作者实际出现次数，算出「允许保留数 = min(实际, 封顶)」。
        Map<String, Integer> tagCount = new HashMap<>();
        Map<String, Integer> authorCount = new HashMap<>();
        for (FeedItemView it : page) {
            String t = primaryTag(it);
            if (t != null) {
                tagCount.merge(t, 1, Integer::sum);
            }
            String a = FeedTimelineStore.authorIdOf(it);
            if (a != null) {
                authorCount.merge(a, 1, Integer::sum);
            }
        }
        Map<String, Integer> tagKeep = new HashMap<>();
        for (Map.Entry<String, Integer> e : tagCount.entrySet()) {
            tagKeep.put(e.getKey(), Math.min(e.getValue(), tagCap));
        }
        Map<String, Integer> authorKeep = new HashMap<>();
        for (Map.Entry<String, Integer> e : authorCount.entrySet()) {
            authorKeep.put(e.getKey(), Math.min(e.getValue(), authorCap));
        }

        // 按入参既定顺序（已是各路合并后的展示序、高位优先）遍历，逐项发配额：
        // 一条内容须同时拿到「标签名额」与「作者名额」才保留，否则裁掉（低位超额尾部）。
        List<FeedItemView> out = new ArrayList<>(page.size());
        Map<String, Integer> tagUsed = new HashMap<>();
        Map<String, Integer> authorUsed = new HashMap<>();
        for (FeedItemView it : page) {
            String t = primaryTag(it);
            String a = FeedTimelineStore.authorIdOf(it);
            boolean tagOk = t == null || tagUsed.getOrDefault(t, 0) < tagKeep.getOrDefault(t, Integer.MAX_VALUE);
            boolean authorOk = a == null || authorUsed.getOrDefault(a, 0) < authorKeep.getOrDefault(a, Integer.MAX_VALUE);
            if (tagOk && authorOk) {
                out.add(it);
                if (t != null) {
                    tagUsed.merge(t, 1, Integer::sum);
                }
                if (a != null) {
                    authorUsed.merge(a, 1, Integer::sum);
                }
            }
        }
        if (out.size() < page.size()) {
            log.info("生态调控裁剪：前 {} 条 → 后 {} 条（整页limit={}, 标签封顶={}, 作者封顶={}）",
                    page.size(), out.size(), limit, tagCap, authorCap);
        }
        return out;
    }

    /** 主标签 = 标签列表首个（用于占比统计；无标签→null 表示豁免调控）。 */
    private static String primaryTag(FeedItemView it) {
        List<String> tags = it == null ? null : it.tags();
        return (tags == null || tags.isEmpty()) ? null : tags.get(0);
    }
}
