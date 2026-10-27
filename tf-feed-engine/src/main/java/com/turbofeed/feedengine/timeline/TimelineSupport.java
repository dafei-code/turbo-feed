package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 公域时间线的<b>共享常量与纯函数</b>（A7「漏斗析出」+ 升级方案 3.5「巨类拆分」的公共底座）。
 *
 * <p><b>为什么要有它</b>：{@code FeedTimelineStore} 原先同时承担「写路径（多池 ZSET + 反查索引）」
 * 与「读路径（推荐漏斗编排）」两套职责，两边共用同一批 key 前缀与纯函数。拆开之后这些成员
 * 若各自复制一份，就会形成「改了写路径、漏了读路径」的静默分歧——这恰是本类要消灭的问题。
 * 因此把它们收敛到一处，双方静态导入，<b>只保留一份真值</b>。</p>
 *
 * <p><b>只放纯函数与常量</b>：无状态、无 Spring 依赖、无 I/O，因此可被写路径、读路径与
 * 单测自由复用。</p>
 *
 * <p><b>M3 变更</b>：原先挂在本类的 {@code parse} 已上移到 {@link com.turbofeed.feedengine.contract.ItemViewCodec}
 * ——它不只被 {@code timeline} 包用，{@code recall} 包也在解析 {@code FeedItemView}，而本类是
 * 包可见的。留在包内就等于让另外 4 处解析点继续裸调 Jackson、绕过版本协商。见
 * {@code docs/architecture/contract-evolution.md} §7。</p>
 *
 * <p><b>P1-1 变更（2026-10-27）</b>：桶键与反查索引值的拼装/解析原先散在
 * {@code FeedTimelineStore}（5 处）与 {@code RecommendationPipeline}（1 处），
 * 且两处对「值以 SOH 开头」这种脏数据的判断恰好相反（{@code sep <= 0} vs {@code sep < 0}）。
 * 现统一由本类的 {@link #bucketKey} / {@link #dateOf} / {@link #indexKey} /
 * {@link #indexValue} / {@link #parseIndex} / {@link #poolOfBucket} / {@link #dateOfBucket}
 * 负责——格式只有一份定义，改一次两边同时生效。</p>
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

    // ===================== 桶键与反查索引：格式的唯一定义 =====================

    /**
     * 单桶最多拉取条数（防单天批准量过大时归并开销爆炸）。
     *
     * <p>放在这里而不是 {@code FeedTimelineStore} 私有：它同时约束<b>写入</b>（append 不设上限）
     * 与<b>读取/扫描</b>（readPage、scanRemove 的 {@code reverseRangeByScore} 上限），
     * 是桶格式的一部分。散在两处时「读改了写没改」不会报错，只会让某些内容读不到。</p>
     */
    static final int PER_BUCKET_CAP = 500;

    /** 桶与反查索引的存活时长：超过该天数的桶整体淘汰（发现流只看最新）。 */
    static final Duration BUCKET_TTL = Duration.ofDays(7);

    /** 桶的日期分片格式（BASIC_ISO_DATE：{@code yyyyMMdd}，可直接按字符串倒序比较）。 */
    private static final DateTimeFormatter BUCKET_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 反查索引值的两段：所在桶 key 与成员串。用 record 而非 {@code String[]}，避免下标语义靠注释传。 */
    record IndexEntry(String bucketKey, String member) {
    }

    /**
     * 桶 key：{@code tf:feed:tl:{pool}:{yyyyMMdd}}。
     *
     * <p><b>为什么必须由本类拼</b>：append / readPage / scanRemove / promote / demote 五处都拼这个键，
     * 任何一处写成 {@code tl:{date}:{pool}} 都会让写入与读取落到两套键上——表现为「发布了但发现流没有」，
     * 而两边代码各自看都是对的。格式只有一个出口，才能靠一次改动同时生效。</p>
     */
    static String bucketKey(int pool, String date) {
        return TL_PREFIX + pool + ":" + date;
    }

    /** 入流时刻 → 桶日期分片（按<b>服务器时区</b>切日；跨时区部署需统一 TZ，否则同一时刻落入不同日桶）。 */
    static String dateOf(Instant inflowAt) {
        return BUCKET_DATE.format(inflowAt.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /** 桶日期分片（{@link #dateOf(Instant)} 的 {@code LocalDate} 重载，供「最近 N 天」遍历使用）。 */
    static String dateOf(LocalDate day) {
        return BUCKET_DATE.format(day);
    }

    /** 反查索引 key：{@code tf:feed:idx:{timelineKey}}。 */
    static String indexKey(String timelineKey) {
        return IDX_PREFIX + timelineKey;
    }

    /** 反查索引值：{@code {桶key}<SOH>{member}}。写入侧唯一出口，与 {@link #parseIndex} 严格对称。 */
    static String indexValue(String bucketKey, String member) {
        return bucketKey + IDX_SEP + member;
    }

    /**
     * 解析反查索引值。格式非法（缺分隔符、桶 key 为空）返回 {@code null}。
     *
     * <p><b>为什么要有唯一解析口</b>：这段格式原先在 6 处各写一遍（Store 的 promote/demote/
     * currentPool/inflowScoreOf/detach + {@code RecommendationPipeline#memberOf}），
     * 而且彼此的边界判断就不一致——Store 用 {@code sep <= 0}、pipeline 用 {@code sep < 0}，
     * 对「值以 SOH 开头」这种脏数据给出相反结论。格式解析一旦分叉，
     * 它的症状不是异常而是<b>静默失效</b>（下架删不掉、晋级找不到桶），最难查。</p>
     */
    static IndexEntry parseIndex(String location) {
        if (location == null) {
            return null;
        }
        int sep = location.indexOf(IDX_SEP);
        if (sep <= 0) {
            // sep < 0：没有分隔符；sep == 0：桶 key 为空 —— 两者都无法定位桶，一并视为非法
            return null;
        }
        return new IndexEntry(location.substring(0, sep), location.substring(sep + IDX_SEP.length()));
    }

    /**
     * 从桶 key 取池号；不是本类格式的桶 key 返回 {@code -1}。
     *
     * <p>比「按固定长度截断 + parseInt 抛异常兜底」更严格：前缀不符直接判定非法，
     * 不再从任意字符串里硬抠出一个数字——抠出来的池号会指向一个真实存在的桶，
     * 于是错误被当成成功写下去（晋级挪错池、下架删错桶）。</p>
     */
    static int poolOfBucket(String bucketKey) {
        String rest = restOfBucket(bucketKey);
        if (rest == null) {
            return -1;
        }
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(rest.substring(0, colon));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从桶 key 取日期分片；格式非法返回 {@code null}（调用方据此放弃搬池，而不是搬到一个拼错的桶里）。 */
    static String dateOfBucket(String bucketKey) {
        String rest = restOfBucket(bucketKey);
        if (rest == null) {
            return null;
        }
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return null;
        }
        return rest.substring(colon + 1);
    }

    /** 去掉 {@link #TL_PREFIX} 前缀后的部分；前缀不符返回 {@code null}。 */
    private static String restOfBucket(String bucketKey) {
        if (bucketKey == null || !bucketKey.startsWith(TL_PREFIX)) {
            return null;
        }
        return bucketKey.substring(TL_PREFIX.length());
    }

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

}
