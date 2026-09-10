package com.turbofeed.gateway.service.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
 * <p><b>方案</b>：审核通过（APPROVED）时，把非范式 {@link MediaItem}（mediaId/url/status/createdAt）
 * 写入 Redis ZSET {@code tf:feed:tl:{yyyyMMdd}}，score = approvedAt 毫秒。读路径
 * {@link #readPage} 走 {@code ZREVRANGEBYSCORE} 游标分页，合并近 {@code MERGE_BUCKETS} 天分桶，
 * 每请求 O(log n) 级、<b>完全不碰 media 分片库</b>。这就是 README 里"Feed 引擎推模式"的
 * 网关内雏形：可见性从"读时扫描"变为"写时产生"。</p>
 *
 * <p><b>按天分桶</b>：避免单 ZSET 无限膨胀（百亿成员会撑爆单 key）；每桶仅承载当天批准量，
 * 读时合并最近 N 天即可覆盖发现流"看最新"的诉求。极端规模可进一步按小时分桶。</p>
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
    /** 合并最近 N 天分桶（覆盖发现流"看最新"诉求，同时限制归并成本）。 */
    private static final int MERGE_BUCKETS = 3;
    /** 单桶最多拉取条数（防单天批准量过大时归并开销爆炸）。 */
    private static final int PER_BUCKET_CAP = 500;

    /** 审核通过：写时间线（denormalized MediaItem JSON），fail-open。仅 APPROVED 入时间线。 */
    public void append(MediaItem item) {
        if (item.status() != MediaStatus.APPROVED) {
            return;
        }
        try {
            String key = TL_PREFIX + bucketOf(item.createdAt());
            redisTemplate.opsForZSet().add(key, objectMapper.writeValueAsString(item), item.createdAt().toEpochMilli());
        } catch (Exception e) {
            log.warn("时间线写入失败（不影响审核主流程）: mediaId={}, {}", item.mediaId(), e.getMessage());
        }
    }

    /**
     * 游标分页读取公域发现流（时间倒序）。合并近 {@code MERGE_BUCKETS} 天分桶，
     * 每桶取最新 {@code PER_BUCKET_CAP} 条归并后切片。
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数
     * @return 时间倒序的内容列表（当前页）；空表示无更多内容或 Redis 不可用
     */
    public List<MediaItem> readPage(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(page, 0) * limit;
        List<MediaItem> candidates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String key = TL_PREFIX + today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            try {
                Set<String> jsons = redisTemplate.opsForZSet()
                        .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                if (jsons != null) {
                    for (String json : jsons) {
                        try {
                            candidates.add(objectMapper.readValue(json, MediaItem.class));
                        } catch (Exception ignore) {
                            // 单条损坏不影响整体
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("时间线读取失败（降级回源由调用方处理）: key={}, {}", key, e.getMessage());
                return List.of();
            }
        }
        candidates.sort(Comparator.comparing(MediaItem::createdAt).reversed());
        int from = (int) Math.min(offset, candidates.size());
        int to = (int) Math.min(offset + limit, candidates.size());
        return candidates.subList(from, to);
    }

    private String bucketOf(Instant t) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(t.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 从公域时间线移除某条内容（用户删除已通过内容时调用）。
     *
     * <p>内容可能落在最近 {@code MERGE_BUCKETS} 天任一桶内，因此遍历这些桶、解析成员比对
     * mediaId 后 {@code ZREM}。fail-open：任一桶异常只告警不抛，不阻断删除主流程。</p>
     *
     * @param mediaId 内容唯一标识
     */
    public void remove(String mediaId) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < MERGE_BUCKETS; d++) {
            String key = TL_PREFIX + today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE);
            try {
                Set<String> jsons = redisTemplate.opsForZSet()
                        .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, PER_BUCKET_CAP);
                if (jsons == null) continue;
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
