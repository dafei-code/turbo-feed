package com.turbofeed.gateway.service.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
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
 * 公域发现流时间线读模型（写时物化，替代跨分片广播扫描）。
 *
 * <p><b>问题</b>：原 {@code MediaJdbcRepository#listApprovedGlobal} 不带分片键，
 * ShardingSphere 广播到全部分片归并——百亿行下每翻一页都全表扫，直接拖垮所有分片。</p>
 *
 * <p><b>方案</b>：审核通过（APPROVED）时，把非范式 {@link MediaItem} 写入 Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}，score = 入流时刻毫秒。读路径 {@link #readPage}
 * 合并近 {@code MERGE_BUCKETS} 天分桶并按 score 归并，每请求 O(log n) 级、
 * <b>完全不碰 media 分片库</b>。这就是 README 里"Feed 引擎推模式"的网关内雏形：
 * 可见性从"读时扫描"变为"写时产生"。</p>
 *
 * <p><b>分桶维度 = 入流（过审）时刻，不是上传时刻</b>：早期实现用 {@link MediaItem#createdAt()}
 * （上传时间）决定桶与 score，于是「上传后隔天才过审」的内容落进旧桶，而读路径只并最近
 * {@code MERGE_BUCKETS} 天 → 该内容<b>永远不会出现在发现流</b>。现统一以
 * {@code Instant.now()}（审核通过入流时刻）分桶与打分，保证「今天过审 → 今天可见」；
 * {@link MediaItem#createdAt()} 仅作为展示用的发布时间，不参与分桶与排序。</p>
 *
 * <p><b>可删除性（合规要求）</b>：ZSET member 是 JSON 串，无法凭 mediaId 直接 {@code ZREM}。
 * 早期实现靠扫描每桶<b>最新 {@code PER_BUCKET_CAP} 条</b>定位目标，桶内超出该量的内容
 * <b>删不掉也下不了架</b>——这对内容安全是硬伤。现为每条内容维护反查索引
 * {@code tf:feed:idx:{mediaId} => {桶key}<SOH>{member}}，删除/下架走精确 {@code ZREM}，
 * 与桶内条数无关；索引缺失（历史数据/已过期）时才退回尽力扫描。</p>
 *
 * <p><b>容量</b>：桶与反查索引统一按 {@link #BUCKET_TTL} 过期，避免逐日累积的 ZSET
 * 无限膨胀（发现流只看最新，过期桶整体淘汰即可）。</p>
 *
 * <p><b>fail-open</b>：写入/读取异常均只告警不抛，绝不影响审核主流程；Redis 不可用时
 * 查询侧回源分片库（降级态，见 {@code MediaQueryService}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TL_PREFIX = "tf:feed:tl:";
    /** 反查索引前缀：mediaId -> 所在桶 key + 成员串（用于精确 ZREM）。 */
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
     * 审核通过：按信用池写时间线（denormalized MediaItem JSON），fail-open。仅 APPROVED 入对应池。
     *
     * <p>分桶与 score 均取<b>当前入流时刻</b>（过审时刻），不取 {@link MediaItem#createdAt()}。
     * 写入前先摘掉该内容可能存在的旧位置，避免「申诉翻案 / 重复过审」让同一内容在流里出现两次。</p>
     */
    public void append(MediaItem item, int poolLevel) {
        if (item.status() != MediaStatus.APPROVED) {
            return;
        }
        int pool = poolLevel < 1 ? 1 : Math.min(poolLevel, MAX_POOL_LEVELS);
        Instant approvedAt = Instant.now();
        String key = TL_PREFIX + pool + ":" + bucketOf(approvedAt);
        try {
            String member = objectMapper.writeValueAsString(item);
            detach(item.mediaId(), null);
            redisTemplate.opsForZSet().add(key, member, approvedAt.toEpochMilli());
            redisTemplate.expire(key, BUCKET_TTL);
            redisTemplate.opsForValue().set(IDX_PREFIX + item.mediaId(), key + IDX_SEP + member, BUCKET_TTL);
        } catch (Exception e) {
            log.warn("时间线写入失败（不影响审核主流程）: mediaId={}, pool={}, {}", item.mediaId(), pool, e.getMessage());
        }
    }

    /** 兼容重载：未指定池时落 L1 小池。 */
    public void append(MediaItem item) {
        append(item, 1);
    }

    /**
     * 游标分页读取公域发现流（入流时间倒序）。合并近 {@code MERGE_BUCKETS} 天分桶，
     * 每桶取最新 {@code PER_BUCKET_CAP} 条归并后切片。
     *
     * <p>归并按 ZSET score（入流时刻）排序，而不是 {@link MediaItem#createdAt()}（上传时间）——
     * 否则「早传晚审」的内容会被排到当日列表末尾，等于隐形。</p>
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<MediaItem> readPage(int page, int size) {
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
                    log.warn("时间线读取失败（降级回源由调用方处理）: key={}, {}", key, e.getMessage());
                    return List.of();
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                (ZSetOperations.TypedTuple<String> t) -> t.getScore() == null ? 0d : t.getScore()).reversed());
        List<MediaItem> items = new ArrayList<>(candidates.size());
        for (ZSetOperations.TypedTuple<String> tuple : candidates) {
            String json = tuple.getValue();
            if (json == null) {
                continue;
            }
            try {
                items.add(objectMapper.readValue(json, MediaItem.class));
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
     * 从公域时间线移除某条内容（用户删除、举报下架、申诉中暂不可见时调用）。
     *
     * <p>优先走反查索引做精确 {@code ZREM}——与桶内条数无关，因此对「桶内超过
     * {@code PER_BUCKET_CAP} 条」的内容同样有效（这正是早期扫描实现的失效场景）。
     * 索引缺失时退回遍历最近 {@code MERGE_BUCKETS} 天桶的尽力扫描（仅覆盖每桶最新
     * {@code PER_BUCKET_CAP} 条，历史数据可能漏删）。fail-open：任一异常只告警不抛，
     * 不阻断删除主流程。</p>
     *
     * @param mediaId 内容唯一标识
     */
    public void remove(String mediaId) {
        try {
            if (detach(mediaId, IDX_PREFIX + mediaId)) {
                return;
            }
        } catch (Exception e) {
            log.warn("时间线移除失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
            return;
        }
        scanRemove(mediaId);
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
    private boolean detach(String mediaId, String idxKeyToDelete) {
        if (mediaId == null) {
            return false;
        }
        String idxKey = IDX_PREFIX + mediaId;
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

    /** 反查索引缺失时的尽力扫描：仅覆盖每桶最新 {@code PER_BUCKET_CAP} 条。 */
    private void scanRemove(String mediaId) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String date = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            for (int pool = 1; pool <= MAX_POOL_LEVELS; pool++) {
                String key = TL_PREFIX + pool + ":" + date;
                try {
                    Set<String> jsons = redisTemplate.opsForZSet()
                            .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                    if (jsons == null) {
                        continue;
                    }
                    for (String json : jsons) {
                        try {
                            MediaItem it = objectMapper.readValue(json, MediaItem.class);
                            if (mediaId.equals(it.mediaId())) {
                                redisTemplate.opsForZSet().remove(key, json);
                            }
                        } catch (Exception ignore) {
                            // 单条损坏不影响整体
                        }
                    }
                } catch (Exception e) {
                    log.warn("时间线移除失败（不影响主流程）: mediaId={}, key={}, {}", mediaId, key, e.getMessage());
                }
            }
        }
    }
}
