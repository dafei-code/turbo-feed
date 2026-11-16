package com.turbofeed.gateway.service;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.trace.TraceContext;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 上传链路的<b>事务收口</b>（PR-B）：把「整帖批量落库」与「MEDIA_UPLOADED 事件行」
 * 放进同一个本地事务，消除「media 行已是 PENDING、审核事件却从未发出」的崩溃窗口。
 *
 * <h3>为什么是独立 Bean + TransactionTemplate，而不是给 upload 加 @Transactional</h3>
 * <ol>
 *   <li><b>不能把外层壳整体包进事务</b>：{@code MediaUploadService#upload} 内含对象存储 IO、
 *       {@code cleanupPartialPost} 手动补偿、{@code finally} 释放占位锁。整包事务会把外部 IO
 *       拖成长事务，且手动补偿与事务回滚互相打架。这里只把<b>两段 DB 写</b>收进事务，
 *       外层壳一行不动。</li>
 *   <li><b>不能靠同类自调用加 @Transactional</b>：{@code MediaUploadFinalizer#finalizeAsync}
 *       走 {@code @Async} 代理，同类内部调用 {@code finalizeInTx()} 会绕过事务代理，
 *       又退回「无事务退化 WARN」那条路。独立 Bean + {@link TransactionTemplate} 没有这个问题。</li>
 *   <li><b>{@code deliverAfterCommit} 必须在事务回调内调用</b>：此时
 *       {@code TransactionSynchronizationManager} 处于激活态，才会注册 afterCommit；
 *       放到外层壳里必然退化成无屏障的立即投递（PR-A 加的 WARN 正是为抓这种漏网）。</li>
 * </ol>
 *
 * <h3>调用方必须遵守的顺序约束（防止幽灵事件）</h3>
 * <p>旧代码把 {@code eventPublisher.publish} 放在 try/catch <b>之外</b>，是刻意的：
 * 只有前面全部成功才发事件，否则 catch 里的 {@code cleanupPartialPost} 会物理删掉刚落的残行。
 * 把事件改到事务内后，如果事务提交后 try 内还有可能抛异常的步骤，cleanup 就会删行、
 * 而 afterCommit 已经把事件发出去了（审核侧兜底落库会「复活」已作废的帖子）。
 * 因此两个调用方都把 {@link #persist} 放在 try 内<b>最后一步</b>，
 * 其后只允许日志等不会抛异常的代码。</p>
 *
 * <h3>灰度</h3>
 * <p>{@code turbofeed.outbox.media-uploaded.enabled=false}（默认）时本类只做
 * 「带事务的 batchInsert」，事件仍由原 {@code MediaEventPublisher} 直发——零行为回归，
 * G3 崩溃窗口在开关打开前显式接受。开关打开后<b>只写 Outbox、不再直发</b>，不双跑，
 * 避免两套可靠性语义并存（消费侧 CAS 幂等虽能兜住双投，但排障要翻两条链路）。</p>
 */
@Component
public class MediaUploadTransaction {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadTransaction.class);

    private final TransactionTemplate transactionTemplate;
    private final MediaJdbcRepository mediaRepository;
    private final OutboxService outboxService;
    private final boolean outboxEnabled;

    public MediaUploadTransaction(PlatformTransactionManager transactionManager,
                                  MediaJdbcRepository mediaRepository,
                                  OutboxService outboxService,
                                  @Value("${" + FeatureFlagKeys.OUTBOX_MEDIA_UPLOADED_ENABLED
                                          + ":false}") boolean outboxEnabled) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mediaRepository = mediaRepository;
        this.outboxService = outboxService;
        this.outboxEnabled = outboxEnabled;
        log.info("上传事务收口就绪: MEDIA_UPLOADED Outbox={}", outboxEnabled);
    }

    /** 供调用方判断是否还要走旧的直发路径（开关打开时不再直发）。 */
    public boolean isOutboxEnabled() {
        return outboxEnabled;
    }

    /**
     * 同事务落「media 行 + 事件行」，并在事务内注册提交后直投。
     *
     * @param rows  整帖全部待落库行（由调用方在事务外组装，组装过程含 IO，不进事务）
     * @param event 帖级上传事件；payload 原样复用，含 {@code occurredAt} / {@code traceId}
     */
    public void persist(List<MediaJdbcRepository.MediaRowSpec> rows, MediaUploadedEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            mediaRepository.batchInsert(rows);
            if (outboxEnabled) {
                long eventId = outboxService.enqueue(OutboxEventType.MEDIA_UPLOADED,
                        event.postId(), userIdOf(event), event.withTraceId(TraceContext.current()));
                // ⚠️ 必须在事务回调内：事务提交后才投，且失败留行由 OutboxRelay 补偿。
                outboxService.deliverAfterCommit(eventId);
            }
        });
    }

    private static Long userIdOf(MediaUploadedEvent event) {
        if (event.userId() == null) {
            return null;
        }
        try {
            return Long.parseLong(event.userId());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
