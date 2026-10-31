package com.turbofeed.gateway.service.notify;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 通知生成器 + 读模型维护（抖音式通知中心，ADR-0009）。
 *
 * <p><b>单点生成</b>：唯一写入 {@code user_notification} 表与 Redis 读模型之处。各业务方只发
 * {@link NotificationEvent}，不直接碰通知表，保证聚合 / 限流 / 已读语义统一。</p>
 *
 * <p><b>写路径（fail-open）</b>：{@link #generate(NotificationEvent)} 异步提交到 {@code interactionExecutor}，
 * 由 {@link #doGenerate} 在后台完成：① 幂等去重（{@code SETNX tf:notify:event:{eventId}}）；
 * ② 落库；③ ZADD 收件箱 ZSET；④ INCR 未读计数。任一异常仅告警，绝不抛回业务方
 * （通知生成失败不能阻塞点赞等主链路）。</p>
 *
 * <p><b>读路径</b>：{@link #inbox} / {@link #unreadCount} / {@link #markRead} 供 {@code NotificationController}
 * 调用。未读计数以 Redis 为快路径、DB 为真相源（Redis 缺失 / 异常回源 COUNT）。</p>
 */
@Service
public class NotificationGenerator {

    private static final Logger log = LoggerFactory.getLogger(NotificationGenerator.class);

    /** 收件箱 ZSET：{@code tf:notify:{userId} => ZSET<notifyId, score=createdAtMs>}。 */
    static final String INBOX_PREFIX = "tf:notify:";
    /** 未读计数：{@code tf:notify:unread:{userId} => 整数}。 */
    static final String UNREAD_PREFIX = "tf:notify:unread:";
    /** 事件幂等去重：{@code tf:notify:event:{eventId} => 1（TTL 1d）}。 */
    static final String EVENT_DEDUP_PREFIX = "tf:notify:event:";
    /** 未读计数 Key TTL（与收件箱解耦，取较长以免角标凭空消失）。 */
    static final Duration UNREAD_TTL = Duration.ofDays(30);
    /** 事件去重 Key TTL：覆盖「至少一次」重投窗口即可。 */
    static final Duration EVENT_DEDUP_TTL = Duration.ofDays(1);

    private final NotificationJdbcRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Executor interactionExecutor;

    public NotificationGenerator(NotificationJdbcRepository repository, StringRedisTemplate redisTemplate,
                                SnowflakeIdGenerator snowflakeIdGenerator,
                                @Qualifier("interactionExecutor") Executor interactionExecutor) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.interactionExecutor = interactionExecutor;
    }

    /**
     * 生成一条通知（异步、fail-open）。业务方构造好已解析接收方的 {@link NotificationEvent} 后调用，
     * 方法立即返回，不阻塞主链路；生成失败仅告警。
     */
    public void generate(NotificationEvent event) {
        try {
            interactionExecutor.execute(() -> doGenerate(event));
        } catch (Exception e) {
            // 执行器拒绝（队列满）等：放弃本次通知生成，绝不阻塞业务方
            log.warn("通知生成任务提交失败（fail-open，丢弃）: eventId={}, type={}, recipient={}, {}",
                    event.eventId(), event.type(), event.recipientId(), e.getMessage());
        }
    }

    private void doGenerate(NotificationEvent event) {
        try {
            // ① 幂等去重：同一 eventId 只生成一次
            if (!tryDedup(event.eventId())) {
                return;
            }
            Instant now = event.createdAt() == null ? Instant.now() : event.createdAt();
            long id = snowflakeIdGenerator.nextId();
            Notification n = new Notification(id, event.recipientId(), event.type().name(), event.actorId(),
                    event.targetType(), event.targetId(), event.summary(), event.refPayload(), false, now, 0L);
            // ② 落库（写模型）
            repository.insert(n);
            // ③ 收件箱 ZSET
            addToInbox(event.recipientId(), id, now.toEpochMilli());
            // ④ 未读 +1
            incrUnread(event.recipientId());
            // （WS 实时推送见 #345）
        } catch (Exception e) {
            log.warn("通知生成失败（fail-open）: eventId={}, type={}, recipient={}, {}",
                    event.eventId(), event.type(), event.recipientId(), e.getMessage());
        }
    }

    /** 收件箱列表（游标分页，附带未读角标）。 */
    public NotificationListView inbox(long userId, long cursor, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        List<Notification> items = repository.listByUser(userId, cursor, n);
        long next = 0L;
        if (items.size() == n) {
            Notification last = items.get(items.size() - 1);
            next = last.createdAt().toEpochMilli();
        }
        return new NotificationListView(items.stream().map(NotificationView::from).toList(), next, unreadCount(userId));
    }

    /** 未读计数（Redis 快路径，DB 真相源回源）。 */
    public long unreadCount(long userId) {
        try {
            String v = redisTemplate.opsForValue().get(unreadKey(userId));
            if (v != null) {
                return Long.parseLong(v);
            }
        } catch (Exception e) {
            log.warn("未读计数读 Redis 失败（回源 DB）: userId={}, {}", userId, e.getMessage());
        }
        try {
            return repository.countUnread(userId);
        } catch (Exception e) {
            log.warn("未读计数回源 DB 亦失败（按 0）: userId={}, {}", userId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 标记已读（按 id 列表或按类型全清）。
     *
     * @param ids  指定 id 列表（与 {@code type} 二选一）
     * @param type 指定类型全清（{@code "ALL"} 或 null = 全部）
     * @return 标记后剩余未读数
     */
    public long markRead(long userId, List<Long> ids, String type) {
        int updated;
        if (ids != null && !ids.isEmpty()) {
            updated = repository.markRead(userId, ids);
        } else if (type != null && !"ALL".equalsIgnoreCase(type) && !type.isBlank()) {
            updated = repository.markAllRead(userId, type.toUpperCase());
        } else {
            updated = repository.markAllRead(userId, null);
        }
        if (updated > 0) {
            decrUnread(userId, updated);
        }
        return unreadCount(userId);
    }

    // ===== Redis 读模型 =====

    private boolean tryDedup(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true; // 无幂等键则放行（不强制去重）
        }
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(eventDedupKey(eventId), "1", EVENT_DEDUP_TTL));
        } catch (Exception e) {
            log.warn("通知事件去重查询失败（放行）: eventId={}, {}", eventId, e.getMessage());
            return true;
        }
    }

    private void addToInbox(long userId, long notifyId, long scoreMs) {
        try {
            redisTemplate.opsForZSet().add(inboxKey(userId), String.valueOf(notifyId), (double) scoreMs);
        } catch (Exception e) {
            log.warn("收件箱 ZSET 写入失败（fail-open）: userId={}, notifyId={}, {}", userId, notifyId, e.getMessage());
        }
    }

    private void incrUnread(long userId) {
        try {
            redisTemplate.opsForValue().increment(unreadKey(userId));
            redisTemplate.expire(unreadKey(userId), UNREAD_TTL);
        } catch (Exception e) {
            log.warn("未读计数 +1 失败（fail-open，DB 为真相源回源）: userId={}, {}", userId, e.getMessage());
        }
    }

    private void decrUnread(long userId, int by) {
        try {
            redisTemplate.opsForValue().decrement(unreadKey(userId), by);
        } catch (Exception e) {
            log.warn("未读计数 -{} 失败（fail-open，DB 为真相源回源）: userId={}, {}", by, userId, e.getMessage());
        }
    }

    private static String inboxKey(long userId) {
        return INBOX_PREFIX + userId;
    }

    private static String unreadKey(long userId) {
        return UNREAD_PREFIX + userId;
    }

    private static String eventDedupKey(String eventId) {
        return EVENT_DEDUP_PREFIX + eventId;
    }

    /** 收件箱单条视图（前端友好，createdAt 以 epoch 毫秒呈现，避免 Jackson Instant 序列化歧义）。 */
    public record NotificationView(long id, String type, Long actorId, String targetType,
                                   String targetId, String summary, String refPayload, boolean read, long createdAtMs) {
        static NotificationView from(Notification n) {
            return new NotificationView(n.id(), n.type(), n.actorId(), n.targetType(), n.targetId(),
                    n.summary(), n.refPayload(), n.read(), n.createdAt().toEpochMilli());
        }
    }

    /** 收件箱分页结果（items + nextCursor + unreadCount）。 */
    public record NotificationListView(List<NotificationView> items, long nextCursor, long unreadCount) {
    }
}
