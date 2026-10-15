package com.turbofeed.feedengine.client;

import com.turbofeed.feedengine.ranking.FeedModerationProperties;
import com.turbofeed.shared.model.PlayableView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 审核信号读取客户端（抖音式「审核与推荐解耦」：推荐侧直读 {@code tf:mod:} KV）。
 *
 * <p><b>为什么是直读 KV 而不是走 HTTP</b>：P2-1 在网关把审核信号写成 Redis Hash（命名空间
 * {@code tf:mod:}），上游推荐系统<b>直读</b>这些 KV 做召回过滤（INTERCEPT/MONITOR）与排序降权
 * （低健康分），不直连审核 MySQL。本类与网关 {@code ModerationSignalService} 方向相反——
 * 后者是<b>生产者</b>，本类是<b>消费者</b>；二者共享同一 Redis 集群、同一 namespace，
 * 但<b>不跨模块依赖</b>（namespace 在本模块独立配置，默认 {@code tf:mod:} 与网关对齐）。</p>
 *
 * <p><b>三个能力</b>：
 * <ol>
 *   <li>{@link #blockedContentKeys}：召回过滤——内容处置为 INTERCEPT/MONITOR 的 postId 集合
 *       （MONITOR 在网关<b>不下架</b>只写 KV，故引擎这层是其公域拦截的<b>唯一手段</b>）；</li>
 *   <li>{@link #blockedAuthorIds}：召回过滤——作者健康分低于阈值或已封禁的 userId 集合
 *       （与 P0-b「限投稿/封禁」语义对齐，不在公域出现）；</li>
 *   <li>{@link #healthScales}：排序降权——DEMOTE 档（健康分 [60,80)）作者系数 = {@code demoteScale}
 *       （默认 0.5，与 P0-b「推荐降权 0.5」逐字对齐），其余档系数 1.0。</li>
 * </ol>
 * </p>
 *
 * <p><b>fail-open（与生产者对称）</b>：KV 是给推荐主流程的<b>加速副本</b>，读失败 / 缺失一律按
 * 「无信号」处理（不拦截、不降权），<b>绝不抛异常、绝不阻断浏览</b>。上游 miss 时本应回源 MySQL，
 * 但推荐主链路选择 fail-open 而非阻塞——最坏只是「漏拦一条」而非「整页不可用」。</p>
 *
 * <p><b>Cluster 跨 slot</b>：不同 postId/userId 落在不同 slot，不能 multiGet，逐个 key 读
 * （O(N)，N 为单页候选数，通常百级内，可接受）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger，不依赖 {@code @Slf4j}。</p>
 */
@Component
public class ReviewSignalClient {

    private static final Logger log = LoggerFactory.getLogger(ReviewSignalClient.class);

    private final StringRedisTemplate redisTemplate;
    private final FeedModerationProperties props;

    // fail-open 可观测性（P1）：tf:mod KV 是推荐主流程的「加速副本」，读失败/缺失一律 fail-open
    // （不拦截、不降权）。这些指标让「fail-open 是否在被悄悄触发」「信号是否真的在生效」可被观测。
    //   · lookups   —— 按 op 统计 KV 读取次数（blockedContent / blockedAuthor / healthScale）
    //   · failures  —— 按 op 统计读异常（fail-open 真正兜底的次数，应为 0，非 0 即 Redis 故障）
    //   · miss      —— 按 op 统计「查了但无信号」的 key 数（缺失/未回源；高 miss 率提示信号未铺满）
    //   · blocked   —— 实际拦截的内容/作者数（验证信号确实在生效）
    //   · demotions —— healthScale<1.0 的降权次数（与 P0-b 降权语义对齐的「降级数」）
    private final Map<String, Counter> lookups = new HashMap<>();
    private final Map<String, Counter> failures = new HashMap<>();
    private final Map<String, Counter> miss = new HashMap<>();
    private final Counter blockedContent;
    private final Counter blockedAuthor;
    private final Counter demotions;

    public ReviewSignalClient(StringRedisTemplate redisTemplate, FeedModerationProperties props, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.props = props;
        for (String op : new String[]{"blockedContent", "blockedAuthor", "healthScale"}) {
            this.lookups.put(op, Counter.builder("tf_feed_moderation_kv_lookups_total")
                    .tag("op", op).description("tf:mod KV 读取次数（按操作）").register(registry));
            this.failures.put(op, Counter.builder("tf_feed_moderation_kv_failures_total")
                    .tag("op", op).description("tf:mod KV 读异常次数（fail-open 兜底，应为 0）").register(registry));
            this.miss.put(op, Counter.builder("tf_feed_moderation_kv_miss_total")
                    .tag("op", op).description("tf:mod KV 查无信号（缺失/未回源）的 key 数").register(registry));
        }
        this.blockedContent = Counter.builder("tf_feed_moderation_kv_blocked_total")
                .tag("kind", "content").description("召回层实际拦截的内容数（INTERCEPT/MONITOR）").register(registry);
        this.blockedAuthor = Counter.builder("tf_feed_moderation_kv_blocked_total")
                .tag("kind", "author").description("召回层实际拦截的作者数（BANNED/低健康分）").register(registry);
        this.demotions = Counter.builder("tf_feed_ranking_demotions_total")
                .description("排序降权次数（healthScale<1.0，与 P0-b 降权 0.5 语义对齐）").register(registry);
    }

    private String mediaKey(String postId) {
        return props.getNamespace() + "media:" + postId;
    }

    private String accountKey(String userId) {
        return props.getNamespace() + "account:" + userId;
    }

    /**
     * 召回过滤：返回需从公域发现流剔除的 postId 集合（内容处置 = INTERCEPT 或 MONITOR）。
     *
     * <p>fail-open：任一异常返回<b>空集</b>——宁可漏拦也不误杀（内容安全靠网关物理下架 + 本层兜底）。</p>
     */
    public Set<String> blockedContentKeys(Set<String> postIds) {
        Set<String> blocked = new LinkedHashSet<>();
        if (postIds == null || postIds.isEmpty()) {
            return blocked;
        }
        try {
            for (String postId : postIds) {
                lookups.get("blockedContent").increment();
                Object action = redisTemplate.opsForHash().get(mediaKey(postId), "action");
                if (action != null && ("INTERCEPT".equals(action) || "MONITOR".equals(action))) {
                    blocked.add(postId);
                    blockedContent.increment();
                } else if (action == null) {
                    // 该 postId 无处置信号（key 缺失或未回源 MySQL）：统计 miss，不拦截（fail-open）
                    miss.get("blockedContent").increment();
                }
            }
        } catch (Exception e) {
            failures.get("blockedContent").increment();
            log.warn("读内容处置信号失败（fail-open，不拦截）: {}", e.getMessage());
        }
        return blocked;
    }

    /**
     * 召回过滤：返回需剔除公域内容的作者 userId 集合（健康分 &lt; {@code dropBelowScore} 或 tier=BANNED）。
     *
     * <p>与 P0-b 阶梯语义对齐：&lt;60（RESTRICT_SUBMIT / RESTRICT_MONETIZE）/ ≤0（BANNED）作者
     * 的内容<b>不该出现在公域发现流</b>，故在召回层硬剔除（而非仅降权）。</p>
     *
     * <p>fail-open：异常返回空集（不误杀）。</p>
     */
    public Set<String> blockedAuthorIds(Set<String> authorIds) {
        Set<String> blocked = new LinkedHashSet<>();
        if (authorIds == null || authorIds.isEmpty()) {
            return blocked;
        }
        try {
            for (String aid : authorIds) {
                lookups.get("blockedAuthor").increment();
                Map<Object, Object> h = redisTemplate.opsForHash().entries(accountKey(aid));
                if (h == null || h.isEmpty()) {
                    miss.get("blockedAuthor").increment();
                    continue;
                }
                String tier = asString(h.get("tier"));
                if ("BANNED".equals(tier)) {
                    blocked.add(aid);
                    blockedAuthor.increment();
                    continue;
                }
                String scoreStr = asString(h.get("score"));
                if (scoreStr != null) {
                    try {
                        int score = Integer.parseInt(scoreStr);
                        if (score < props.getDropBelowScore()) {
                            blocked.add(aid);
                            blockedAuthor.increment();
                        }
                    } catch (NumberFormatException ignore) {
                        // 脏数据跳过该作者，不影响其余
                    }
                }
            }
        } catch (Exception e) {
            failures.get("blockedAuthor").increment();
            log.warn("读账号健康分信号失败（fail-open，不拦截）: {}", e.getMessage());
        }
        return blocked;
    }

    /**
     * 播放前复校辅助：仅返回处置为 {@code INTERCEPT}（拦截下架）的 postId 集合，
     * <b>不含 {@code MONITOR}</b>——MONITOR 在抖音式语义里只是「限公域 / 排序降权」，内容仍
     * 可播放，不应触发「非公平态 5s 刷新」的打断。与 {@link #blockedContentKeys} 的区别仅在于
     * 是否把 MONITOR 计入拦截。fail-open：异常返回空集（不拦截）。
     */
    public Set<String> interceptedContentKeys(Set<String> postIds) {
        Set<String> blocked = new LinkedHashSet<>();
        if (postIds == null || postIds.isEmpty()) {
            return blocked;
        }
        try {
            for (String postId : postIds) {
                Object action = redisTemplate.opsForHash().get(mediaKey(postId), "action");
                if ("INTERCEPT".equals(action)) {
                    blocked.add(postId);
                }
            }
        } catch (Exception e) {
            failures.get("blockedContent").increment();
            log.warn("读内容处置信号失败（fail-open，不拦截）: {}", e.getMessage());
        }
        return blocked;
    }

    /**
     * 播放前复校辅助：返回作者被拦截的具体原因（{@code ACCOUNT_BANNED} / {@code ACCOUNT_LOW_SCORE} /
     * {@code null}=未拦截），供 {@code PlayabilityService} 细分 reason。
     *
     * <p>与 {@link #blockedAuthorIds} 同源（同一 {@code tf:mod:account} hash、同一阈值），但额外
     * 区分「封禁」与「低健康分」两种语义；fail-open：读异常 / 缺失 / 脏数据一律返回 {@code null}
     * （不拦截）。本方法仅在作者已被 {@code blockedAuthorIds} 判为拦截后才调用，额外读仅发生在
     * 少量被拦截作者上，成本可忽略。</p>
     */
    public String authorBlockReason(String authorId) {
        if (authorId == null || authorId.isBlank()) {
            return null;
        }
        try {
            Map<Object, Object> h = redisTemplate.opsForHash().entries(accountKey(authorId));
            if (h == null || h.isEmpty()) {
                return null;
            }
            if ("BANNED".equals(asString(h.get("tier")))) {
                return PlayableView.REASON_ACCOUNT_BANNED;
            }
            String scoreStr = asString(h.get("score"));
            if (scoreStr != null) {
                try {
                    int score = Integer.parseInt(scoreStr);
                    if (score < props.getDropBelowScore()) {
                        return PlayableView.REASON_ACCOUNT_LOW_SCORE;
                    }
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 排序降权：单作者 userId 的排序系数。仅 DEMOTE 档（健康分 [60,80)）返回 {@code demoteScale}
     * （默认 0.5，与 P0-b「推荐降权 0.5」逐字对齐），其余档 / 缺失 / 读失败均返回 1.0（fail-open 不降权）。
     *
     * <p>设计为「单作者」而非批量：调用方（{@code FeedTimelineStore#scoreOf}）按 authorId 懒加载并
     * 在本页内缓存，避免排序前先全量预取；BANNED/&lt;60 作者已在召回层剔除，不会走到这里。</p>
     *
     * <p>fail-open：异常/缺失返回 1.0（不降权，绝不阻断浏览）。</p>
     */
    public double healthScaleOf(String authorId) {
        if (authorId == null || authorId.isBlank()) {
            return 1.0d;
        }
        try {
            lookups.get("healthScale").increment();
            Map<Object, Object> h = redisTemplate.opsForHash().entries(accountKey(authorId));
            if (h == null || h.isEmpty()) {
                miss.get("healthScale").increment();
                return 1.0d;
            }
            if ("DEMOTE".equals(asString(h.get("tier")))) {
                demotions.increment();
                return props.getDemoteScale();
            }
            return 1.0d;
        } catch (Exception e) {
            failures.get("healthScale").increment();
            log.warn("读账号健康分信号失败（fail-open，不降权）: authorId={}, {}", authorId, e.getMessage());
            return 1.0d;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
