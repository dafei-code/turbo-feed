package com.turbofeed.gateway.service.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ShareRepository;
import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.service.notify.NotificationEvent;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.notify.NotificationType;
import com.turbofeed.gateway.service.query.MediaQueryService;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.service.social.FollowService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import com.turbofeed.shared.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 短链分享服务（方案 §4 的落地）。
 *
 * <p><b>两条链路</b>：create 把"内容身份 + 卡片快照"固化为短码并写 MySQL/Redis；
 * resolve 凭短码还原映射后<b>回源校验内容当前状态</b>——短链只是入口，不是内容副本，
 * 已下架内容不能借短链复活（合规红线，方案 §4.5）。</p>
 *
 * <p><b>失败口径</b>：核心写路径失败抛 {@link ShareException} 由 Controller 转错误码；
 * Redis 缓存读写全部 fail-open（缓存是旁路，不是权威）。通知 / 计数是附加副作用，
 * 失败只记日志，不回滚已创建的短链。</p>
 */
@Slf4j
@Service
public class ShareService {

    private static final String CACHE_PREFIX = "tf:share:code:";
    private static final String RATE_PREFIX = "tf:share:rl:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final int MAX_CODE_RETRY = 3;

    private final ShareRepository shareRepository;
    private final ShareCodeGenerator codeGenerator;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MediaJdbcRepository mediaRepository;
    private final MediaQueryService mediaQueryService;
    private final FollowService followService;
    private final NotificationGenerator notificationGenerator;
    private final UploadRateLimiter rateLimiter;
    private final ObjectProvider<CounterEventPublisher> counterPublisher;
    private final String baseUrl;

    public ShareService(ShareRepository shareRepository,
                        ShareCodeGenerator codeGenerator,
                        SnowflakeIdGenerator idGenerator,
                        StringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper,
                        MediaJdbcRepository mediaRepository,
                        MediaQueryService mediaQueryService,
                        FollowService followService,
                        NotificationGenerator notificationGenerator,
                        UploadRateLimiter rateLimiter,
                        ObjectProvider<CounterEventPublisher> counterPublisher,
                        @Value("${" + FeatureFlagKeys.SHARE_BASE_URL + ":}") String baseUrl) {
        this.shareRepository = shareRepository;
        this.codeGenerator = codeGenerator;
        this.idGenerator = idGenerator;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.mediaRepository = mediaRepository;
        this.mediaQueryService = mediaQueryService;
        this.followService = followService;
        this.notificationGenerator = notificationGenerator;
        this.rateLimiter = rateLimiter;
        this.counterPublisher = counterPublisher;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    /**
     * 创建短链。仅允许分享<b>本人已发布</b>的内容（与 API 错误码口径一致）。
     *
     * @param sharerId  分享者（JWT 身份）
     * @param postId    内容身份（= 时间线 timelineKey）
     * @param friendIds 可选：互关好友列表，逐个发站内通知
     */
    public CreateResult create(long sharerId, String postId, List<Long> friendIds) {
        if (postId == null || postId.isBlank()) {
            throw new ShareException(ErrorCode.PARAM_ERROR, "postId 不能为空");
        }
        MediaJdbcRepository.PostRepresentative post = mediaRepository.findRepresentativeByPostId(postId);
        if (post == null) {
            throw new ShareException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        if (post.status() != MediaStatus.APPROVED) {
            throw new ShareException(ErrorCode.PARAM_ERROR, "仅已发布内容可分享");
        }
        if (post.authorId() != sharerId) {
            throw new ShareException(ErrorCode.PARAM_ERROR, "只能分享自己的内容");
        }
        if (!rateLimiter.tryAcquire(String.valueOf(sharerId), RATE_PREFIX)) {
            throw new ShareException(ErrorCode.RATE_LIMITED, "分享过于频繁，请稍后再试");
        }

        String snapshotJson = buildSnapshot(postId, post, sharerId);
        ShareRecord created = null;
        for (int attempt = 0; attempt < MAX_CODE_RETRY; attempt++) {
            long id = idGenerator.nextId();
            String code = codeGenerator.encode(id);
            // SETNX 预占：挡住 Redis 侧并发；DB 的 uk_code 是第二道
            Boolean reserved;
            try {
                reserved = redisTemplate.opsForValue().setIfAbsent(CACHE_PREFIX + code, snapshotJson, CACHE_TTL);
            } catch (Exception e) {
                log.warn("短码 Redis 预占失败，改由 DB 唯一索引兜底: code={}, {}", code, e.getMessage());
                reserved = true;
            }
            if (!Boolean.TRUE.equals(reserved)) {
                continue;
            }
            ShareRecord record = new ShareRecord(code, id, postId, post.mediaId(), post.authorId(),
                    sharerId, snapshotJson, ShareRecord.STATUS_ACTIVE, Instant.now());
            try {
                if (shareRepository.insert(record) == 1) {
                    created = record;
                    break;
                }
            } catch (Exception e) {
                log.warn("短码落库冲突，换码重试: code={}, {}", code, e.getMessage());
            }
            // 预占成功但落库失败：清掉占位，避免幽灵 code 挡住后续重试
            try {
                redisTemplate.delete(CACHE_PREFIX + code);
            } catch (Exception ignored) {
                // Redis 不可达时占位自带 TTL，7 天后自然回收
            }
        }
        if (created == null) {
            throw new ShareException(ErrorCode.INTERNAL_ERROR, "短链生成失败，请重试");
        }

        // 分享计数：只统计"实际创建成功的短链"（方案 §4.7 / §8.1），跳转 /s/{code} 不重复计数。
        // 与 Redis 计数（如点赞）不同，分享数只做聚合底座，不参与去重 Set 的幂等口径。
        notifyCounter(created.postId());
        int notified = notifyFriends(created, friendIds);
        return new CreateResult(created.code(), shortPath(created.code()), shortUrl(created.code()),
                created.snapshotJson(), notified);
    }

    /**
     * 跳转解析：先查缓存、miss 回源 DB，再校验内容当前状态。
     *
     * @return 内容已不可用或短码不存在时返回 {@code null}
     */
    public ResolveResult resolve(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        ShareRecord record = load(code);
        if (record == null || record.status() != ShareRecord.STATUS_ACTIVE) {
            return null;
        }
        MediaStatus status;
        try {
            status = mediaQueryService.statusOf(record.mediaId(), String.valueOf(record.ownerId()));
        } catch (Exception e) {
            // 状态校验失败一律按不可用处理：宁可拒绝，不能让已下架内容借短链复活
            log.warn("短链状态校验失败，按不可用处理: code={}, {}", code, e.getMessage());
            return null;
        }
        if (status != MediaStatus.APPROVED) {
            return null;
        }
        return new ResolveResult(record.postId(), record.ownerId(), record.snapshotJson());
    }

    /** 我的分享列表（返回下一页游标；无更多时为 0）。 */
    public MyShareList myShares(long sharerId, long cursor, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<ShareRecord> rows = shareRepository.listByUser(sharerId, cursor, size);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        List<ShareView> views = new ArrayList<>(rows.size());
        for (ShareRecord r : rows) {
            views.add(new ShareView(r.code(), shortPath(r.code()), shortUrl(r.code()),
                    r.postId(), r.snapshotJson(), r.createdAt()));
        }
        return new MyShareList(views, hasMore ? rows.get(rows.size() - 1).id() : 0L);
    }

    // ==================== 内部实现 ====================

    private ShareRecord load(String code) {
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + code);
            if (cached != null) {
                ShareRecord record = objectMapper.readValue(cached, ShareRecord.class);
                if (record != null && code.equals(record.code())) {
                    return record;
                }
            }
        } catch (Exception e) {
            log.warn("短链缓存读取失败，回源 DB: code={}, {}", code, e.getMessage());
        }
        Optional<ShareRecord> found = shareRepository.findByCode(code);
        if (found.isEmpty()) {
            return null;
        }
        ShareRecord record = found.get();
        try {
            redisTemplate.opsForValue().set(CACHE_PREFIX + code,
                    objectMapper.writeValueAsString(record), CACHE_TTL);
        } catch (Exception e) {
            log.warn("短链缓存回填失败（不影响本次跳转）: code={}, {}", code, e.getMessage());
        }
        return record;
    }

    /** 快照 caption 上限：H2 测试表 snapshot_json 是 VARCHAR(4000)，截到 200 字符留足 JSON 转义余量。 */
    private static final int SNAPSHOT_CAPTION_MAX = 200;

    private String buildSnapshot(String postId, MediaJdbcRepository.PostRepresentative post, long sharerId) {
        // caption 是帖子级属性，代表行（seq=0）已带出；空值兜底为空串，超长截断防溢出测试表列。
        String caption = post.caption() == null ? "" : post.caption();
        if (caption.length() > SNAPSHOT_CAPTION_MAX) {
            caption = caption.substring(0, SNAPSHOT_CAPTION_MAX);
        }
        try {
            ShareSnapshot snapshot = new ShareSnapshot(postId, post.mediaId(), post.url(), caption,
                    post.authorId(), sharerId, Instant.now().toEpochMilli());
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new ShareException(ErrorCode.INTERNAL_ERROR, "分享快照生成失败");
        }
    }

    /** 分享给互关好友：关系校验在服务端做，前端勾选不可信。 */
    private int notifyFriends(ShareRecord record, List<Long> friendIds) {
        if (friendIds == null || friendIds.isEmpty()) {
            return 0;
        }
        Set<Long> targets = new LinkedHashSet<>(friendIds);
        targets.remove(record.sharerId());
        int sent = 0;
        for (long friendId : targets) {
            if (friendId <= 0) {
                continue;
            }
            try {
                boolean mutual = followService.isFollowing(record.sharerId(), friendId)
                        && followService.isFollower(record.sharerId(), friendId);
                if (!mutual) {
                    continue;
                }
                String eventId = "SHARE:" + record.code() + ":" + friendId;
                notificationGenerator.generate(new NotificationEvent(eventId, NotificationType.SHARE,
                        friendId, record.sharerId(), "POST", record.postId(),
                        "给你分享了一个作品", "{\"code\":\"" + record.code() + "\"}", Instant.now()));
                sent++;
            } catch (Exception e) {
                // 通知是附加副作用：失败不能让已创建的短链变成 500
                log.warn("分享通知生成失败（fail-open）: code={}, friendId={}, {}",
                        record.code(), friendId, e.getMessage());
            }
        }
        return sent;
    }

    /**
     * 发分享计数增量给 tf-counter 聚合底座（opt-in，fail-open）。
     *
     * <p>与 {@code MediaLikeService#notifyCounter} 同范式：用 {@link ObjectProvider} 是为了在
     * {@code turbofeed.counter.service.enabled=false}（默认）时 Bean 不存在也不影响分享主流程；
     * 计数是附加副作用，失败只告警、不回滚已创建的短链。</p>
     */
    private void notifyCounter(String postId) {
        try {
            counterPublisher.ifAvailable(c -> c.incr(postId, "SHARE", 1));
        } catch (Exception e) {
            log.warn("分享计数增量通知 tf-counter 失败（fail-open，不影响主流程）: postId={}, {}",
                    postId, e.getMessage());
        }
    }

    private String shortPath(String code) {
        return "/s/" + code;
    }

    private String shortUrl(String code) {
        return baseUrl.isEmpty() ? shortPath(code) : baseUrl + shortPath(code);
    }

    // ==================== 视图 ====================

    /** 卡片快照：只存元数据，不复制内容主体。 */
    public record ShareSnapshot(String postId, String mediaId, String coverUrl, String caption,
                                long ownerId, long sharerId, long createdAt) {
    }

    /** 创建结果。 */
    public record CreateResult(String code, String shortPath, String shortUrl,
                               String snapshotJson, int notifiedFriends) {
    }

    /** 跳转解析结果。 */
    public record ResolveResult(String postId, long ownerId, String snapshotJson) {
    }

    /** 我的分享条目。 */
    public record ShareView(String code, String shortPath, String shortUrl, String postId,
                            String snapshotJson, Instant createdAt) {
    }

    /** 我的分享分页。 */
    public record MyShareList(List<ShareView> items, long nextCursor) {
    }
}
