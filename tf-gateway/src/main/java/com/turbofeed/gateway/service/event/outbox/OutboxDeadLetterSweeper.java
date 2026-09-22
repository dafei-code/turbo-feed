package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 死信重投扫描器（DEAD 终态的出口）。
 *
 * <p>发件箱原状态机到 DEAD 即终止、既不复投也无告警（at-least-once 在 DEAD 处断裂）。
 * 本类周期扫 DEAD 行，把未达重投上限的重新 open 成 PENDING（带冷却），交给既有
 * {@code OutboxRelay} 投递——<b>不重复投递逻辑</b>，只负责「给一次重新机会」。</p>
 *
 * <p>{@code TIMELINE_REMOVE}（下架）类比 {@code TIMELINE_APPEND}（入流）更急：冷却更短，
 * 尽快把下架内容从公域摘掉。</p>
 *
 * <p>与 {@code OutboxRelay} 同受 {@code relay-enabled} 开关控制：关掉则失去死信重投（仅保留首批告警）。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDeadLetterSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeadLetterSweeper.class);

    private final OutboxEventRepository repository;
    private final DeadLetterAlert deadLetterAlert;
    private final int batchSize;
    private final long deadCooldownSeconds;
    private final long removeDeadCooldownSeconds;
    private final int maxDeadRedeliveries;

    public OutboxDeadLetterSweeper(OutboxEventRepository repository,
                                   DeadLetterAlert deadLetterAlert,
                                   @Value("${turbofeed.outbox.dead-batch-size:100}") int batchSize,
                                   @Value("${turbofeed.outbox.dead-cooldown-seconds:3600}") long deadCooldownSeconds,
                                   @Value("${turbofeed.outbox.remove-dead-cooldown-seconds:300}") long removeDeadCooldownSeconds,
                                   @Value("${turbofeed.outbox.max-dead-redeliveries:3}") int maxDeadRedeliveries) {
        this.repository = repository;
        this.deadLetterAlert = deadLetterAlert;
        this.batchSize = batchSize;
        this.deadCooldownSeconds = deadCooldownSeconds;
        this.removeDeadCooldownSeconds = removeDeadCooldownSeconds;
        this.maxDeadRedeliveries = maxDeadRedeliveries;
    }

    @Scheduled(initialDelayString = "${turbofeed.outbox.dead-initial-delay-ms:60000}",
               fixedDelayString = "${turbofeed.outbox.dead-poll-ms:300000}")
    public void sweep() {
        Instant now = Instant.now();
        List<Long> ids = repository.findDeadIds(batchSize, now, maxDeadRedeliveries);
        if (ids.isEmpty()) {
            return;
        }
        int reopened = 0;
        for (long id : ids) {
            Optional<OutboxEventRepository.OutboxEvent> opt = repository.findById(id);
            if (opt.isEmpty()) {
                continue;
            }
            OutboxEventRepository.OutboxEvent e = opt.get();
            boolean remove = OutboxEventType.TIMELINE_REMOVE.name().equals(e.eventType());
            long cooldown = remove ? removeDeadCooldownSeconds : deadCooldownSeconds;
            if (repository.reDeadLetter(id, cooldown, now) == 1) {
                reopened++;
                log.warn("死信重投: id={}, type={}, 重新进入 PENDING(冷却 {}s)", id, e.eventType(), cooldown);
            }
        }
        if (reopened > 0) {
            log.info("死信重投一轮: 重开 {} 条", reopened);
        }
    }
}
