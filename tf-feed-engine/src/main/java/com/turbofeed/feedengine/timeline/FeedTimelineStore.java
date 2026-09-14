package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 公域发现流时间线读模型（写时物化）。
 *
 * <p><b>从 tf-gateway 迁入</b>：本类原属网关 {@code com.turbofeed.gateway.service.feed}，
 * 是"Feed 引擎推模式"在网关进程内的雏形。服务拆分（见 docs/architecture/service-split.md）
 * 后，Feed 时间线的物化与读取整体归位到本引擎——网关只保留 HTTP 接入与审核状态机，
 * 通过内部接口投递变更，不再直接持有任何 Feed 读模型。</p>
 *
 * <p><b>解决的问题</b>：原 {@code MediaJdbcRepository#listApprovedGlobal} 不带分片键，
 * ShardingSphere 广播到全部分片归并——百亿行下每翻一页都全表扫，直接拖垮所有分片。
 * 本类把可见性从"读时扫描"变为"写时产生"：审核通过即写入 Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}，读路径合并近 {@link #MERGE_BUCKETS} 天分桶按 score
 * 归并，每请求 O(log n) 级、完全不碰 media 分片库。</p>
 *
 * <p><b>分桶维度 = 入流（过审）时刻，不是上传时刻</b>：早期实现用
 * {@link FeedItemView#createdAt()}（上传时间）决定桶与 score，于是「上传后隔天才过审」的内容
 * 落进旧桶，而读路径只并最近 {@code MERGE_BUCKETS} 天 → 该内容<b>永远不会出现在发现流</b>。
 * 现统一以 {@code Instant.now()}（过审入流时刻）分桶与打分，保证「今天过审 → 今天可见」；
 * {@code createdAt} 仅作为展示用的发布时间，不参与分桶与排序。</p>
 *
 * <p><b>一个成员 = 一个帖子（一帖多图）</b>：物化单位是<b>帖子</b>而非单张图——同一次上传批次的
 * N 张图（{@link FeedItemView#images()}）共用一条成员串，前端据此渲染 9 图轮播。因此本类所有
 * 「定位一条内容」的地方统一使用 {@link FeedItemView#timelineKey()}（有 {@code postId} 用 postId，
 * 历史单图成员串回退 {@code mediaId}）作为幂等键与反查索引键；调用方不要各自拼装这个键，
 * 否则新旧数据会落在两个不同索引上，出现「下架无效、内容仍可见」。</p>
 *
 * <p><b>可删除性（合规要求）</b>：ZSET member 是 JSON 串，无法凭帖身份直接 {@code ZREM}。
 * 早期实现靠扫描每桶<b>最新 {@code PER_BUCKET_CAP} 条</b>定位目标，桶内超出该量的内容
 * <b>删不掉也下不了架</b>——这对内容安全是硬伤。现为每个帖子维护反查索引
 * {@code tf:feed:idx:{timelineKey} => {桶key}<SOH>{member}}，删除/下架走精确 {@code ZREM}，
 * 与桶内条数无关；索引缺失（历史数据/已过期）时才退回尽力扫描。</p>
 *
 * <p><b>容量</b>：桶与反查索引统一按 {@link #BUCKET_TTL} 过期，避免逐日累积的 ZSET
 * 无限膨胀（发现流只看最新，过期桶整体淘汰即可）。</p>
 *
 * <p><b>fail-open（默认路径）</b>：{@link #append}/{@link #remove} 写入/读取异常均只告警不抛，
 * 绝不影响调用方主流程，HTTP 兜底投递路径仍用它。需强一致（异常上抛以触发 MQ 重试/DLQ）时，
 * 调用方改用 {@link #appendStrict}/{@link #removeStrict}（B2 顺序消息消费者即用此路径）。
 * 注意本类位于引擎侧后，"读不到"不再回源分片库（引擎无 DB 依赖），降级口径由网关按
 * {@code turbofeed.feed.degraded-mode} 决定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TL_PREFIX = "tf:feed:tl:";
    /** 反查索引前缀：帖身份（postId，历史数据回退 mediaId）-&gt; 所在桶 key + 成员串（用于精确 ZREM）。 */
    private static final String IDX_PREFIX = "tf:feed:idx:";
    /** 合并最近 N 天分桶（覆盖发现流"看最新"诉求，同时限制归并成本）。 */
    private static final int MERGE_BUCKETS = 3;
    /** 单桶最多拉取条数（防单天批准量过大时归并开销爆炸）。 */
    private static final int PER_BUCKET_CAP = 500;
    /** 流量池层级上限（L1 小池 / L3 大池，演示 3 级）。 */
    private static final int MAX_POOL_LEVELS = 3;
    /** 桶与反查索引的存活时长：超过该天数的桶整体淘汰（发现流只看最新）。 */
    private static final Duration BUCKET_TTL = Duration.ofDays(7);
    /** 反查索引值里「桶 key」与「成员串」的分隔符（SOH 不可打印字符，不会出现在 key 与 JSON 中）。 */
    private static final String IDX_SEP = "\u0001";

    /**
     * 审核通过：按信用池写时间线（denormalized {@link FeedItemView} JSON），fail-open。
     * 仅 {@code status=APPROVED} 入对应池。
     *
     * <p>分桶与 score 均取<b>当前入流时刻</b>（过审时刻），不取 {@code createdAt}。
     * 写入前先摘掉该内容可能存在的旧位置，避免「申诉翻案 / 重复过审」让同一内容在流里出现两次。</p>
     */
    public void append(FeedItemView item, int poolLevel) {
        try {
            appendStrict(item, poolLevel);
        } catch (Exception e) {
            log.warn("时间线写入失败（不影响主流程）: mediaId={}, pool={}, {}", item == null ? "null" : item.mediaId(), poolLevel, e.getMessage());
        }
    }

    /**
     * 严格写入入口（B2 顺序消息消费者使用）：不做 try/catch，异常向上抛，
     * 由 MQ 框架触发重试 / 进入 DLQ。{@code item} 为空或未过审时无声返回（不入时间线）。
     */
    public void appendStrict(FeedItemView item, int poolLevel) {
        if (item == null || !item.approved()) {
            return;
        }
        int pool = poolLevel < 1 ? 1 : Math.min(poolLevel, MAX_POOL_LEVELS);
        Instant approvedAt = Instant.now();
        String key = TL_PREFIX + pool + ":" + bucketOf(approvedAt);
        // 帖身份：有 postId 用 postId，历史单图数据回退 mediaId（见 FeedItemView#timelineKey）
        String timelineKey = item.timelineKey();
        String member;
        try {
            member = objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            // 严格路径：序列化失败必须上抛，交由 MQ 重试 / DLQ，绝不可静默吞掉。
            throw new IllegalStateException("Feed 时间线序列化失败（不入时间线）: key=" + timelineKey, e);
        }
        detach(timelineKey, null);
        redisTemplate.opsForZSet().add(key, member, approvedAt.toEpochMilli());
        redisTemplate.expire(key, BUCKET_TTL);
        redisTemplate.opsForValue().set(IDX_PREFIX + timelineKey, key + IDX_SEP + member, BUCKET_TTL);
    }

    /** 兼容重载：未指定池时落 L1 小池。 */
    public void append(FeedItemView item) {
        append(item, 1);
    }

    /**
     * 游标分页读取公域发现流（入流时间倒序）。合并近 {@code MERGE_BUCKETS} 天分桶，
     * 每桶取最新 {@code PER_BUCKET_CAP} 条归并后切片。
     *
     * <p>归并按 ZSET score（入流时刻）排序，而不是 {@code createdAt}（上传时间）——
     * 否则「早传晚审」的内容会被排到当日列表末尾，等于隐形。</p>
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<FeedItemView> readPage(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(page, 0) * limit;
        List<ZSetOperations.TypedTuple<String>> candidates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            // 合并所有流量池（L1..Ln）近 N 天：发布即进对应池，读时统一聚合
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = TL_PREFIX + pool + ":" + date;
                try {
                    Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                            .reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY,
                                    Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                    if (tuples != null) {
                        candidates.addAll(tuples);
                    }
                } catch (Exception e) {
                    log.warn("时间线读取失败: key={}, {}", key, e.getMessage());
                    return List.of();
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                (ZSetOperations.TypedTuple<String> t) -> t.getScore() == null ? 0d : t.getScore()).reversed());
        List<FeedItemView> items = new ArrayList<>(candidates.size());
        for (ZSetOperations.TypedTuple<String> tuple : candidates) {
            String json = tuple.getValue();
            if (json == null) {
                continue;
            }
            try {
                items.add(objectMapper.readValue(json, FeedItemView.class));
            } catch (Exception ignore) {
                // 单条损坏不影响整体
            }
        }
        int from = (int) Math.min(offset, items.size());
        int to = (int) Math.min(offset + limit, items.size());
        return items.subList(from, to);
    }

    private String bucketOf(Instant t) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(t.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 从公域时间线移除某个<b>帖子</b>（用户删除、举报下架、申诉中暂不可见时调用）。
     *
     * <p>优先走反查索引做精确 {@code ZREM}——与桶内条数无关，因此对「桶内超过
     * {@code PER_BUCKET_CAP} 条」的内容同样有效（这正是早期扫描实现的失效场景）。
     * 索引缺失时退回遍历最近 {@code MERGE_BUCKETS} 天桶的尽力扫描（仅覆盖每桶最新
     * {@code PER_BUCKET_CAP} 条，历史数据可能漏删）。fail-open：任一异常只告警不抛。</p>
     *
     * @param timelineKey 帖身份（{@code FeedItemView#timelineKey()}：有 postId 用 postId，
     *                    历史单图数据回退 mediaId）。<b>必须与投递时一致</b>，否则反查索引对不上、下架失效
     */
    public void remove(String timelineKey) {
        try {
            removeStrict(timelineKey);
        } catch (Exception e) {
            log.warn("时间线移除失败（不影响主流程）: key={}, {}", timelineKey, e.getMessage());
        }
    }

    /**
     * 严格移除入口（B2 顺序消息消费者使用）：不做 try/catch，异常向上抛，
     * 由 MQ 框架触发重试 / 进入 DLQ。优先走反查索引精确 {@code ZREM}，
     * 索引缺失时退回尽力扫描。
     *
     * @param timelineKey 帖身份——必须与 {@code append} 时写入的键一致（{@code FeedItemView#timelineKey()}）
     */
    public void removeStrict(String timelineKey) {
        if (timelineKey == null) {
            return;
        }
        if (detach(timelineKey, IDX_PREFIX + timelineKey)) {
            return;
        }
        scanRemoveStrict(timelineKey);
    }

    /**
     * 按反查索引把内容从原桶摘除，并按需删除索引本身。
     *
     * <p>{@code append} 内部复用它做「先摘旧位置」——此时传 {@code null} 只摘除、保留索引，
     * 随后的写入会覆盖为新位置。</p>
     *
     * @param idxKeyToDelete 需要一并删除的索引 key；{@code null} 表示保留
     * @return 是否命中索引（false 表示该内容没有索引，调用方可决定是否退回扫描）
     */
    private boolean detach(String timelineKey, String idxKeyToDelete) {
        if (timelineKey == null) {
            return false;
        }
        String idxKey = IDX_PREFIX + timelineKey;
        String location = redisTemplate.opsForValue().get(idxKey);
        if (location == null) {
            return false;
        }
        int sep = location.indexOf(IDX_SEP);
        if (sep > 0) {
            redisTemplate.opsForZSet().remove(location.substring(0, sep),
                    location.substring(sep + IDX_SEP.length()));
        }
        if (idxKeyToDelete != null) {
            redisTemplate.delete(idxKeyToDelete);
        }
        return true;
    }

    /**
     * 反查索引缺失时的尽力扫描（严格路径使用）：仅覆盖每桶最新 {@code PER_BUCKET_CAP} 条。
     *
     * <p><b>严格语义</b>：不像 fail-open 版本那样吞掉基础设施异常 —— Redis 读取 / ZREM 失败必须上抛，
     * 否则消费者会误以为处理成功而 ACK，下架被静默丢失（与本方法存在的意义相悖）。
     * 只对「单条成员串 JSON 损坏」保持容忍：那是数据问题，重试不会变好，跳过该条继续扫其余。</p>
     */
    private void scanRemoveStrict(String timelineKey) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = TL_PREFIX + pool + ":" + date;
                Set<String> jsons = redisTemplate.opsForZSet()
                        .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                if (jsons == null) {
                    continue;
                }
                for (String json : jsons) {
                    FeedItemView it;
                    try {
                        it = objectMapper.readValue(json, FeedItemView.class);
                    } catch (JsonProcessingException e) {
                        // 单条成员串损坏：数据问题而非基础设施故障，重试无意义 → 跳过继续
                        log.warn("时间线成员串损坏，跳过该条: key={}, {}", key, e.getMessage());
                        continue;
                    }
                    // 用帖身份比对（历史单图成员串没有 postId，timelineKey() 会回退到 mediaId），
                    // 因此新旧数据都能被同一次扫描命中，不需要分两套匹配逻辑。
                    if (timelineKey.equals(it.timelineKey())) {
                        redisTemplate.opsForZSet().remove(key, json);
                    }
                }
            }
        }
    }
}
