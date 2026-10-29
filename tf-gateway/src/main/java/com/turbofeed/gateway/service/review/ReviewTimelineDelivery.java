package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.MediaTagJdbcRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.event.outbox.TimelineAppendPayload;
import com.turbofeed.gateway.service.event.outbox.TimelineRemovePayload;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.caption.CaptionTagParser;
import com.turbofeed.shared.redis.RedisKeys;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 时间线投递子服务（#310 从 {@link MediaReviewService} 拆出）：负责「审核状态 ↔ 公域时间线可见性」的
 * 入流 / 摘除 / 缓存失效 / 整帖视图构建，以及 mediaId 解析归属 userId。
 *
 * <p><b>无状态协作 Bean</b>：所有方法都在调用方（{@link MediaReviewService} 门面）的 {@code @Transactional}
 * 内执行，自身不声明事务——投递依赖的发件箱事件行必须与状态翻转写在<b>同一个本地事务</b>里
 * （见 changelog 0037 / P0-3），否则事务回滚会发出「DB 里不存在的帖子」的幽灵消息。</p>
 */
@Service
public class ReviewTimelineDelivery {

    private static final Logger log = LoggerFactory.getLogger(ReviewTimelineDelivery.class);

    /** 审核状态缓存键前缀（旁路缓存 fail-open，见 {@link #evictCache}）。 */
    private static final String STATUS_KEY_PREFIX = RedisKeys.MEDIA_STATUS;

    private final MediaJdbcRepository mediaRepository;
    private final MediaTagJdbcRepository mediaTagRepository;
    private final StringRedisTemplate redisTemplate;
    private final OutboxService outboxService;

    public ReviewTimelineDelivery(MediaJdbcRepository mediaRepository,
                                  MediaTagJdbcRepository mediaTagRepository,
                                  StringRedisTemplate redisTemplate,
                                  OutboxService outboxService) {
        this.mediaRepository = mediaRepository;
        this.mediaTagRepository = mediaTagRepository;
        this.redisTemplate = redisTemplate;
        this.outboxService = outboxService;
    }

    /**
     * 事件 → 公域时间线条目（物化进 Redis ZSET 的 {@code FeedItemView} JSON，含描述/标题与整帖图片）。
     *
     * <p>描述与图片列表随事件透传而非回查 DB：审核发布是热路径，避免为拿 caption 与 images
     * 多两轮分片查询；事件与首落库携带同一份数据，物化结果与库中一致，Feed 直接可展示。</p>
     */
    static MediaItem toTimelinePost(MediaUploadedEvent event) {
        String representativeId = event.representativeMediaId();
        String cover = event.urls().isEmpty() ? null : event.urls().get(0);
        return new MediaItem(event.postId(), representativeId, cover, event.urls(), 0,
                MediaStatus.APPROVED, event.occurredAt(), event.caption(), event.captionMark(),
                CaptionTagParser.parse(event.caption()), 0L, 0L, false);
    }

    /**
     * 把<b>行级</b>条目升级为可入流的<b>整帖</b>条目：补齐帖内全部图片（按 seq 升序）。
     *
     * <p>{@code findMedia} 返回的行级视图 {@code images} 只含自身，直接入流会让一帖九图在
     * 公域只显示一张。历史单图帖（{@code post_id} 为空）天然只有一张，走单元素列表。</p>
     */
    MediaItem asTimelinePost(MediaItem row, long userId) {
        if (row == null) {
            return null;
        }
        List<String> images;
        if (row.postId() == null || row.postId().isBlank()) {
            images = row.url() == null ? List.of() : List.of(row.url());
        } else {
            List<MediaItem> rows = mediaRepository.listPostImages(userId, row.postId());
            images = new ArrayList<>(rows.size());
            for (MediaItem r : rows) {
                if (r.url() != null) {
                    images.add(r.url());
                }
            }
            if (images.isEmpty()) {
                images = row.url() == null ? List.of() : List.of(row.url());
            }
        }
        return new MediaItem(row.postId(), row.mediaId(), row.url(), images, 0,
                MediaStatus.APPROVED, row.createdAt(), row.caption(), row.captionMark(),
                CaptionTagParser.parse(row.caption()), 0L, 0L, false);
    }

    /**
     * 把帖从公域时间线摘除：<b>必须用与投递时相同的键</b>（有 postId 用 postId，
     * 历史单图数据回退 mediaId），否则新旧数据会落在两个反查索引上，下架失效。
     */
    void removeFromTimeline(String mediaId, long userId) {
        String postId = mediaRepository.findPostId(mediaId, userId);
        String key = postId == null || postId.isBlank() ? mediaId : postId;
        // 改走发件箱：下架是「安全动作」，丢了比晚了更糟——内容已在 DB 下架，
        // 若 remove 投递丢失，公域会一直展示已下架内容（改造前正是这个 fail-open）。
        // 顺序安全：事件按雪花 id 有序消费，且 append 在过审时就已直投，
        // remove 一定晚于它，不存在「remove 先到、append 后到」把内容又放回来的情况。
        publishRemove(mediaId, userId, key);
        // 内容标签冷存同步摘除：key 与投递时同口径（见 publishAppend），否则新旧标签
        // 会落在两个索引上。best-effort：清理失败不影响 content 已在 DB 下架这一事实。
        mediaTagRepository.remove(key);
    }

    /** 入流：同事务落发件箱事件 + 提交后直投（失败留行，由 OutboxRelay 补偿重投）。 */
    void publishAppend(String mediaId, long userId, MediaItem post, int poolLevel) {
        long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_APPEND, mediaId, userId,
                new TimelineAppendPayload(post, poolLevel));
        outboxService.deliverAfterCommit(eventId);
        // 内容标签冷存同步写入：键用 post.timelineKey()（= Redis tf:media:tags 同源），
        // 标签由 caption 解析、随 post 透传，避免二次解析分叉。best-effort：冷存写入失败
        // 不阻断发布链路（Redis 才是读源，本表可重建）。
        mediaTagRepository.save(post.timelineKey(), post.tags());
    }

    /** 移出公域：同上，走 TIMELINE_REMOVE。 */
    private void publishRemove(String mediaId, long userId, String timelineKey) {
        long eventId = outboxService.enqueue(OutboxEventType.TIMELINE_REMOVE, mediaId, userId,
                new TimelineRemovePayload(timelineKey));
        outboxService.deliverAfterCommit(eventId);
    }

    /** 从 mediaId（media/{userId}/{uuid}.{ext}）解析归属 userId，用于审核接口分片路由。 */
    static long parseUserId(String mediaId) {
        String[] parts = mediaId.split("/");
        if (parts.length < 2) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的 mediaId: " + mediaId);
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mediaId 中的 userId 非法: " + mediaId);
        }
    }

    /**
     * 失效<b>整帖</b>的状态缓存（旁路缓存 fail-open）。
     *
     * <p>状态缓存按 {@code mediaId} 分键（{@code tf:media:status:{mediaId}}），而状态翻转是整帖的：
     * 只清代表行会让同帖其余行在 60s TTL 内继续返回旧状态——「我的内容」若按任意行读状态就可能
     * 显示「审核中」而公域已可见。故这里把帖内全部 mediaId 的缓存一并清掉。</p>
     */
    void evictCache(String mediaId, long userId) {
        try {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(STATUS_KEY_PREFIX + mediaId);
            String postId = mediaRepository.findPostId(mediaId, userId);
            if (postId != null && !postId.isBlank()) {
                for (MediaItem row : mediaRepository.listPostImages(userId, postId)) {
                    keys.add(STATUS_KEY_PREFIX + row.mediaId());
                }
            }
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("状态缓存失效失败（不影响主流程）: mediaId={}, {}", mediaId, e.getMessage());
        }
    }
}
