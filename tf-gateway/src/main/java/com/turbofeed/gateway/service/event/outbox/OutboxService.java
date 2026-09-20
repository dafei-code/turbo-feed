package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.repository.OutboxEventRepository;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Instant;

/**
 * 发件箱服务：落事件 + 投递（快路径与补偿路径共用同一段投递逻辑）。
 *
 * <h3>两条投递路径，一个实现</h3>
 * <ol>
 *   <li><b>快路径（低延迟）</b>：事务提交后立即直投一次
 *       （{@link #deliverAfterCommit} 注册 {@code afterCommit}）。绝大多数请求在这一步就完成，
 *       用户感知不到发件箱的存在。</li>
 *   <li><b>补偿路径（保可靠）</b>：直投失败或进程崩溃时，事件行仍在库里（状态 PENDING/FAILED/卡死的 SENDING），
 *       由 {@code OutboxRelay} 轮询取出并调用同一个 {@link #tryDeliver}。</li>
 * </ol>
 * 二者共用 {@link #tryDeliver} 是刻意的：<b>补偿路径必须与正常路径走完全相同的代码</b>，
 * 否则补偿逻辑会因为长期不被执行而腐化，等真正需要它时已经不可用。</p>
 *
 * <p><b>为什么快路径失败不抛异常</b>：此时事务已提交（内容已可见），抛异常既撤不回事务、
 * 又会把「可补偿的失败」变成「用户可见的 500」。正确动作是记日志 + 留行待补偿。
 * 这正是发件箱相对改造前「afterCommit 里 catch 一下就完了」的本质区别：<b>失败有去处</b>。</p>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository repository;
    private final FeedTimelinePublisher feedTimelinePublisher;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final int maxAttempts;
    private final int backoffSeconds;

    public OutboxService(OutboxEventRepository repository,
                         FeedTimelinePublisher feedTimelinePublisher,
                         ObjectMapper objectMapper,
                         SnowflakeIdGenerator snowflakeIdGenerator,
                         @Value("${turbofeed.outbox.max-attempts:5}") int maxAttempts,
                         @Value("${turbofeed.outbox.backoff-seconds:5}") int backoffSeconds) {
        this.repository = repository;
        this.feedTimelinePublisher = feedTimelinePublisher;
        this.objectMapper = objectMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.maxAttempts = maxAttempts;
        this.backoffSeconds = backoffSeconds;
    }

    /**
     * 在<b>调用方事务内</b>写一条待投递事件，返回事件 id。
     *
     * <p>⚠️ 必须在事务中调用：发件箱的全部价值就是「事件行与业务数据同生共死」。
     * 无事务调用会把「提交后投递失败 → 消息丢失」这个老问题原样带回来。</p>
     */
    public long enqueue(OutboxEventType type, String aggregateId, Long userId, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败是代码/契约问题，重试一万次也一样——直接抛，让它打断事务，
            // 而不是写进库里变成一条永远投不出去的 DEAD 行。
            throw new IllegalStateException("发件箱事件序列化失败: type=" + type + ", aggregateId=" + aggregateId, e);
        }
        long id = snowflakeIdGenerator.nextId();
        return repository.insert(id, type, aggregateId, userId, json, maxAttempts, Instant.now());
    }

    /**
     * 注册「事务提交后直投一次」。提交前不投（否则事务回滚就发出了幽灵消息），
     * 提交失败也不投（同步回调不会执行）。
     */
    public void deliverAfterCommit(long eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务（如测试直调）：退化成立即投递，保持语义可用。
            tryDeliver(eventId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tryDeliver(eventId);
            }
        });
    }

    /**
     * 尝试投递一条事件（快路径与补偿路径共用）。
     *
     * @return true = 投递成功（已标记 SENT）；false = 未投递（已被他人领走、已终态、或本次失败待重试）
     */
    public boolean tryDeliver(long eventId) {
        var opt = repository.findById(eventId);
        if (opt.isEmpty()) {
            return false;
        }
        OutboxEventRepository.OutboxEvent event = opt.get();
        if (!repository.claim(eventId, Instant.now())) {
            // 已被另一个实例 / 上一轮中继领走，或已是终态：不重复投递（幂等的第一道防线）。
            return false;
        }
        try {
            dispatch(event);
            repository.markSent(eventId, Instant.now());
            log.info("发件箱投递成功: id={}, type={}, aggregateId={}", eventId, event.eventType(), event.aggregateId());
            return true;
        } catch (Exception e) {
            // claim 时已把 attempt_count +1，这里回读才能拿到真实的累计次数来决定退避/判死。
            OutboxEventRepository.OutboxEvent fresh = repository.findById(eventId).orElse(event);
            repository.markFailed(eventId, fresh.attemptCount(), fresh.maxAttempts(),
                    String.valueOf(e), backoffSeconds, Instant.now());
            log.error("发件箱投递失败，留待中继重试: id={}, type={}, aggregateId={}, attempt={}/{}",
                    eventId, event.eventType(), event.aggregateId(), fresh.attemptCount(), fresh.maxAttempts(), e);
            return false;
        }
    }

    /** 按事件类型分发投递。新增类型必须在这里补分支，否则 {@code switch} 穷尽性检查会编译报错。 */
    private void dispatch(OutboxEventRepository.OutboxEvent event) throws IOException {
        switch (OutboxEventType.valueOf(event.eventType())) {
            case TIMELINE_APPEND -> {
                TimelineAppendPayload payload = objectMapper.readValue(event.payload(), TimelineAppendPayload.class);
                feedTimelinePublisher.append(payload.item(), payload.poolLevel());
            }
            case TIMELINE_REMOVE -> {
                TimelineRemovePayload payload = objectMapper.readValue(event.payload(), TimelineRemovePayload.class);
                feedTimelinePublisher.remove(payload.timelineKey());
            }
        }
    }
}
