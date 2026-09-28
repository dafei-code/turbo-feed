package com.turbofeed.feedengine.interest;

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
 */
@Service
public class TagIndexService {

    private final StringRedisTemplate redisTemplate;

    /** 标签 → 内容集合。 */
    private static final String TAG_TO_MEDIA = "tf:tag:media:";
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
        if (timelineKey == null || tags == null || tags.isEmpty()) {
            return;
        }
        try {
            String contentKey = MEDIA_TO_TAGS + timelineKey;
            for (String tag : tags) {
                redisTemplate.opsForSet().add(TAG_TO_MEDIA + tag, timelineKey);
                redisTemplate.expire(TAG_TO_MEDIA + tag, TTL);
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
        if (timelineKey == null) {
            return;
        }
        try {
            String contentKey = MEDIA_TO_TAGS + timelineKey;
            Set<String> tags = redisTemplate.opsForSet().members(contentKey);
            if (tags != null) {
                for (String tag : tags) {
                    redisTemplate.opsForSet().remove(TAG_TO_MEDIA + tag, timelineKey);
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
