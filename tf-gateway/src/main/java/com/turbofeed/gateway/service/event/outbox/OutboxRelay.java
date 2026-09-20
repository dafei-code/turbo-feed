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

/**
 * 发件箱中继：把库里「待投递 / 失败待重试 / 卡死待回收」的事件投出去（P0-3 的补偿通道）。
 *
 * <p><b>它只在异常情况下才真正干活</b>：正常请求由 {@link OutboxService#deliverAfterCommit}
 * 在事务提交后直投完成，中继轮询到的应该永远是空列表。这不是浪费——轮询是「保险费」，
 * 保费极低（一次带索引的小查询），赔付的是「内容已可见却没进时间线」这种难排查的静默故障。</p>
 *
 * <h3>多实例与并发</h3>
 * <p>多实例部署时每个实例都会轮询，靠 {@link OutboxEventRepository#claim} 的 CAS
 * （PENDING/FAILED → SENDING）保证同一行只被一个实例投一次；其余实例 CAS 落空直接跳过。
 * 这里刻意<b>不做</b>选主：选主会引入「主挂了谁接管」的新问题，而 CAS 天然无主、无协调成本。</p>
 *
 * <p><b>可关</b>：{@code turbofeed.outbox.relay-enabled=false}。关掉不影响事件落库与快路径直投，
 * 只是失去补偿——仅在排障（例如怀疑中继刷爆下游）时临时使用。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final OutboxService outboxService;
    private final int batchSize;
    private final long stuckTimeoutSeconds;

    public OutboxRelay(OutboxEventRepository repository,
                       OutboxService outboxService,
                       @Value("${turbofeed.outbox.batch-size:100}") int batchSize,
                       @Value("${turbofeed.outbox.stuck-timeout-seconds:60}") long stuckTimeoutSeconds) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.batchSize = batchSize;
        this.stuckTimeoutSeconds = stuckTimeoutSeconds;
    }

    /**
     * 一轮补偿：先回收卡死行，再投递到期行。
     *
     * <p>{@code fixedDelay}（而非 {@code fixedRate}）：上一轮没跑完就不会开下一轮，
     * 避免慢下游时中继自身堆积成雪崩。</p>
     */
    @Scheduled(initialDelayString = "${turbofeed.outbox.initial-delay-ms:10000}",
               fixedDelayString = "${turbofeed.outbox.poll-interval-ms:2000}")
    public void relay() {
        Instant now = Instant.now();
        int reaped = repository.reapStuck(stuckTimeoutSeconds, now);
        if (reaped > 0) {
            // 出现即意味着「有实例在投递途中崩溃过」——正常不该出现，值得运维看见。
            log.warn("发件箱回收卡死投递 {} 行（进程崩溃残留，SENDING 超时 {}s → FAILED）", reaped, stuckTimeoutSeconds);
        }
        List<Long> ids = repository.findDueIds(batchSize, now);
        if (ids.isEmpty()) {
            return;
        }
        int ok = 0;
        for (long id : ids) {
            if (outboxService.tryDeliver(id)) {
                ok++;
            }
        }
        log.info("发件箱补偿一轮: due={}, 成功={}, 失败={}", ids.size(), ok, ids.size() - ok);
    }
}
