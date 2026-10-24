package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;

import java.util.Set;

/**
 * 公域时间线的<b>共享常量与纯函数</b>（A7「漏斗析出」+ 升级方案 3.5「巨类拆分」的公共底座）。
 *
 * <p><b>为什么要有它</b>：{@code FeedTimelineStore} 原先同时承担「写路径（多池 ZSET + 反查索引）」
 * 与「读路径（推荐漏斗编排）」两套职责，两边共用同一批 key 前缀与纯函数。拆开之后这些成员
 * 若各自复制一份，就会形成「改了写路径、漏了读路径」的静默分歧——这恰是本类要消灭的问题。
 * 因此把它们收敛到一处，双方静态导入，<b>只保留一份真值</b>。</p>
 *
 * <p><b>只放纯函数与常量</b>：无状态、无 Spring 依赖、无 I/O（{@code parse} 只做反序列化，
 * 不碰 Redis），因此可被写路径、读路径与单测自由复用。</p>
 */
final class TimelineSupport {

    private TimelineSupport() {
    }

    // ===================== Redis key 前缀与池常量 =====================
    static final String TL_PREFIX = "tf:feed:tl:";

    /** 反查索引前缀：帖身份（postId，历史数据回退 mediaId）-&gt; 所在桶 key + 成员串（用于精确 ZREM）。 */
    static final String IDX_PREFIX = "tf:feed:idx:";

    /** 作者内容索引前缀（关注流 G6 召回用）：authorId -&gt; ZSET(成员串 → 入流时刻毫秒)。 */
    static final String AUTHOR_PREFIX = "tf:feed:author:";

    /** 反查索引值里「桶 key」与「成员串」的分隔符（SOH 不可打印字符，不会出现在 key 与 JSON 中）。 */
    static final String IDX_SEP = "\u0001";

    /** 合并最近 N 天分桶（覆盖发现流"看最新"诉求，同时限制归并成本）。 */
    static final int MERGE_BUCKETS = 3;

    /** 流量池层级上限（L1 小池 / L3 大池，演示 3 级）。包可见：晋级器据此判断封顶。 */
    static final int MAX_POOL_LEVELS = 3;

    /** 兴趣召回的过量取倍数：过滤掉与热点/流量池重复的后仍要够填满槽位。 */
    static final int RECALL_OVERFETCH = 3;

    /** 关注流召回：单次最多扫描的关注作者数（限制 Redis 往返，避免大 V 关注爆炸）。 */
    static final int FOLLOW_SCAN_LIMIT = 50;

    /** 关注流召回：每位关注作者最多取其近期已审内容条数。 */
    static final int FOLLOW_PER_AUTHOR = 3;

    /**
     * 兴趣召回位的时间序取值：以<b>内容创建时间</b>作代理。
     *
     * <p>时间线 ZSET 的 score 是"入流时刻（过审时刻）"，但兴趣召回的内容来自标签索引，
     * 不必然落在当前合并窗口的分桶里，因此拿不到它的入流 score。
     * 创建时间与入流时刻同为 epoch 毫秒、量纲一致，作为代理是安全的——
     * 且召回的内容受标签索引 7 天 TTL 约束，二者相差有限。</p>
     */
    static double recencyOf(FeedItemView item) {
        if (item == null || item.createdAt() == null) {
            return 0d;
        }
        return item.createdAt().toEpochMilli();
    }

    /**
     * 内容是否命中该用户的负向标签（至少一个标签相交即判定命中）。
     *
     * <p>用"相交"而非"全部匹配"：短视频的负面标签往往是场景化的（比如在"宠物"下点了不感兴趣，
     * 是因为不喜欢某类养宠内容而非所有动物），只要内容<b>沾到</b>被否定的标签就应当沉底——
     * 这与正向兴趣"累加多个标签命中分"形成对称：正向是加分累积，负向是一票否决。</p>
     */
    static boolean matchesNegative(FeedItemView item, Set<String> negative) {
        if (negative == null || negative.isEmpty() || item.tags() == null) {
            return false;
        }
        for (String tag : item.tags()) {
            if (negative.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 单条候选的键收集：postId(=timelineKey) 入 posts，authorId(从 postId 解析) 入 authors。 */
    static void collectModerationKeys(FeedItemView it, Set<String> posts, Set<String> authors) {
        if (it == null) {
            return;
        }
        String tk = it.timelineKey();
        if (tk != null) {
            posts.add(tk);
        }
        String aid = authorIdOf(it);
        if (aid != null) {
            authors.add(aid);
        }
    }

    /** 该候选是否应被召回层剔除（内容处置 INTERCEPT/MONITOR，或作者健康分低于阈值/封禁）。 */
    static boolean isModerationBlocked(FeedItemView it, Set<String> blockedPosts, Set<String> blockedAuthors) {
        String tk = it.timelineKey();
        if (tk != null && blockedPosts.contains(tk)) {
            return true;
        }
        String aid = authorIdOf(it);
        return aid != null && blockedAuthors.contains(aid);
    }

    /**
     * 从条目身份解析作者 userId：postId 形如 {@code post/{userId}/{uuid}}（历史单图回退 mediaId
     * {@code media/{userId}/{uuid}.ext}），取首段斜杠后的 userId。与网关写入 {@code tf:mod:account:{userId}}
     * 的键约定对齐；解析失败返回 {@code null}（fail-open：不剔除、不降权）。
     */
    static String authorIdOf(FeedItemView item) {
        return authorIdFromKey(item == null ? null : item.timelineKey());
    }

    /**
     * 从帖身份解析作者 userId：postId 形如 {@code post/{userId}/{uuid}}（历史单图回退 mediaId
     * {@code media/{userId}/{uuid}.ext}），取首段斜杠后的 userId。与网关写入 {@code tf:mod:account:{userId}}
     * 的键约定对齐；解析失败返回 {@code null}（fail-open：不剔除、不降权）。
     */
    static String authorIdFromKey(String pk) {
        if (pk == null || pk.isEmpty()) {
            return null;
        }
        int first = pk.indexOf('/');
        if (first < 0) {
            return null;
        }
        int second = pk.indexOf('/', first + 1);
        return second > 0 ? pk.substring(first + 1, second) : null;
    }

    /**
     * 反序列化时间线成员串（{@code ObjectMapper} 由调用方注入，本类不持有状态）。
     *
     * <p>任何异常一律返回 {@code null}：成员串可能是历史格式（缺 postId/images），
     * 也可能被人为写坏，读路径宁可丢这一条也不能整页失败（fail-open）。</p>
     */
    static FeedItemView parse(ObjectMapper objectMapper, String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FeedItemView.class);
        } catch (Exception ignore) {
            return null;
        }
    }
}
