package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.redis.RedisKeys;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 内容标签 ⇄ 内容的反向索引（Redis，抖音式兴趣召回的基础设施）。
 *
 * <p><b>为什么在引擎侧、且只放 Redis</b>：标签随「审核通过入流」那一刻随 {@link com.turbofeed.shared.model.FeedItemView}
 * 物化进时间线成员串（已含 {@code tags}），本服务只是把同一份标签再冗余成两套查询索引：
 * <ul>
 *   <li>{@code tf:tag:media:{tag}} → 该标签下的内容 timelineKey 集合（按标签找内容，未来 recall）；</li>
 *   <li>{@code tf:media:tags:{timelineKey}} → 某内容挂了哪些标签（行为发生时按 liked 内容反查其标签，喂兴趣画像）。</li>
 * </ul>
 * 二者同源、随 append/remove 维护，TTL 与时间线分桶一致（{@code 7d}），过期自动淘汰。</p>
 *
 * <p><b>与 media_tag 库表的关系</b>：本索引是 MVP 的标签存储（写时物化、读路径全走 Redis）；
 * 落库为持久标签表（{@code media_tag}，按 post_id 128 分片）作为系统记录、可重建本索引，
 * 属后续增强，不在 MVP 热路径读取，故不阻塞本期。</p>
 *
 * <p><b>fail-open</b>：任一 Redis 异常只告警不抛——标签索引是推荐优化项，绝不能让入流/下架主流程失败。</p>
 *
 * <p><b>⚠️ 调用方纪律：本索引不归 {@code FeedTimelineStore} 维护，两条投递路径必须各自显式调用。</b>
 * <ul>
 *   <li>HTTP 兜底路径：{@code FeedTimelineController#append} / {@code #remove}；</li>
 *   <li>RocketMQ 顺序消息路径：{@code FeedTimelineConsumer#onMessage}。</li>
 * </ul>
 * 历史上消费者漏调过——{@code mq.enabled=true} 时索引既不建也不摘，且<b>无任何报错</b>：
 * 表现为「兴趣召回召回不到、搜索搜不到」以及「已下架内容仍留在倒排链」。新增投递路径时务必对齐。</p>
 */
@Service
public class TagIndexService {

    private final StringRedisTemplate redisTemplate;

    /** 标签 → 内容集合。 */
    private static final String TAG_TO_MEDIA = RedisKeys.TAG_MEDIA;
    /**
     * 标签 → 内容<b>有序</b>集合（ZSET，score = 入流时间戳毫秒）。
     *
     * <p><b>为什么在 SET 之外再建一个 ZSET</b>：SET 无 score，按时间翻页只能 {@code SMEMBERS}
     * 全量拉回再排序——大话题（十万级）一次往返就打爆。ZSET 的 {@code ZREVRANGEBYSCORE}
     * 天然支持「按时间倒序 + 游标翻页」，是搜索（S1）的读路径主索引。
     * 两者<b>同源双写</b>：SET 供既有兴趣召回读，ZSET 供搜索翻页读；灰度完成后再收敛为单写。</p>
     */
    private static final String TAG_TO_MEDIA_ORDERED = RedisKeys.TAG_MEDIA_ORDERED;
    /**
     * 联想词典（全局唯一一个键，member 恒 score=0 才能用 {@code ZRANGEBYLEX} 做前缀扫描）。
     *
     * <p>成员格式 {@code tag:{tag}}；网关侧 {@code SearchIndexWriter} 往同一个键写
     * {@code user:{nickname}}。两侧共享同一 Redis 键，<b>改名必须同步改网关</b>。</p>
     */
    private static final String SEARCH_LEX = "tf:search:lex";
    /** 词典里话题成员的类型前缀（与网关 {@code SearchIndexWriter#LEX_TAG} 对齐）。 */
    private static final String LEX_TAG_PREFIX = "tag:";
    /** 内容 → 标签集合（兴趣累积时按 liked 内容反查）。 */
    private static final String MEDIA_TO_TAGS = "tf:media:tags:";
    /** 与发现流分桶 TTL 对齐，避免标签索引比内容本身活得更久造成脏数据。 */
    private static final Duration TTL = Duration.ofDays(7);

    public TagIndexService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 入流时建立标签索引（幂等：重复 append 同标签只是 SADD 去重）。
     *
     * @param timelineKey 内容身份（postId，历史数据回退 mediaId）；{@code null} 忽略
     * @param tags        标签列表（小写、去重）；空/{} 忽略
     */
    public void index(String timelineKey, List<String> tags) {
        // 空白键也拦截：否则会写出 tf:tag:media:z:{tag} → "  " 这类永远读不回来的脏成员
        if (timelineKey == null || timelineKey.isBlank() || tags == null || tags.isEmpty()) {
            return;
        }
        try {
            String contentKey = MEDIA_TO_TAGS + timelineKey;
            // 同一帖的多个标签共用一个时间戳：翻页时同帖内容不会因毫秒差被打散
            long score = System.currentTimeMillis();
            for (String tag : tags) {
                redisTemplate.opsForSet().add(TAG_TO_MEDIA + tag, timelineKey);
                // 有序倒排（搜索读路径）：缺它则搜索只能 SMEMBERS 全量拉回再排序
                redisTemplate.opsForZSet().add(TAG_TO_MEDIA_ORDERED + tag, timelineKey, score);
                // 联想词典：score 恒 0（ZRANGEBYLEX 的前提）；词典是全局词表，不设 TTL
                redisTemplate.opsForZSet().add(SEARCH_LEX, LEX_TAG_PREFIX + tag, 0d);
                redisTemplate.expire(TAG_TO_MEDIA + tag, TTL);
                redisTemplate.expire(TAG_TO_MEDIA_ORDERED + tag, TTL);
            }
            redisTemplate.opsForSet().add(contentKey, tags.toArray(new String[0]));
            redisTemplate.expire(contentKey, TTL);
        } catch (Exception e) {
            // fail-open：标签索引缺失只影响个性化召回，不影响内容可见性
            org.slf4j.LoggerFactory.getLogger(TagIndexService.class)
                    .warn("标签索引写入失败（不影响主流程）: timelineKey={}, {}", timelineKey, e.getMessage());
        }
    }

    /**
     * 下架时摘除标签索引：先按内容反查其标签，再从各 {@code tf:tag:media:{tag}} 移除该内容。
     *
     * @param timelineKey 内容身份（须与 {@link #index} 同键，否则反查不到、索引滞留，由 TTL 兜底清理）
     */
    public void remove(String timelineKey) {
        if (timelineKey == null || timelineKey.isBlank()) {
            return;
        }
        try {
            String contentKey = MEDIA_TO_TAGS + timelineKey;
            Set<String> tags = redisTemplate.opsForSet().members(contentKey);
            if (tags != null) {
                for (String tag : tags) {
                    redisTemplate.opsForSet().remove(TAG_TO_MEDIA + tag, timelineKey);
                    // 有序倒排必须同步摘除，否则已下架内容仍能被搜索命中
                    redisTemplate.opsForZSet().remove(TAG_TO_MEDIA_ORDERED + tag, timelineKey);
                }
            }
            redisTemplate.delete(contentKey);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TagIndexService.class)
                    .warn("标签索引摘除失败（不影响主流程）: timelineKey={}, {}", timelineKey, e.getMessage());
        }
    }

    /** 取某内容挂的标签（兴趣累积时按被点赞/评论内容的 timelineKey 反查）。 */
    public Set<String> tagsOf(String timelineKey) {
        if (timelineKey == null) {
            return Set.of();
        }
        try {
            Set<String> tags = redisTemplate.opsForSet().members(MEDIA_TO_TAGS + timelineKey);
            return tags == null ? Set.of() : tags;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 取带某标签的内容集合（未来按标签 recall 用）。 */
    public Set<String> mediaWithTag(String tag) {
        if (tag == null) {
            return Set.of();
        }
        try {
            Set<String> set = redisTemplate.opsForSet().members(TAG_TO_MEDIA + tag);
            return set == null ? Set.of() : set;
        } catch (Exception e) {
            return Set.of();
        }
    }
}
