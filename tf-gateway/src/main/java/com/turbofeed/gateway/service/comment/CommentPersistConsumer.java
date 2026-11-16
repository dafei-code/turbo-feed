package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.trace.TraceContext;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 评论创建事件消费者（opt-in，{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>消费 {@code turbofeed-comment-created} Topic（与网关 {@link RocketMqCommentEventPublisher} 共用），
 * 经 {@link CommentJdbcRepository#insertIfAbsent} 严格幂等落库。</p>
 *
 * <p><b>PR-A：strict 消费</b>：落库异常不再 catch 后 warn 就 ack。RocketMQ 的 at-least-once
 * 只有在上抛时才成立——异常会让容器返回 RECONSUME_LATER，走重试队列，耗尽后入 DLQ；
 * 且普通 INSERT 不再吞非主键 SQL 错误，超长 content / 断连等都能被看见。</p>
 *
 * <p><b>优化项④ 接线</b>：插入成功后推进 {@link CommentPageCache} 的 generation，让该 mediaId
 * 的全部评论页缓存立即失效。重复消费（inserted=false）不重复失效；缓存默认关闭时 evict 是 no-op。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED, havingValue = "true")
@RocketMQMessageListener(topic = "turbofeed-comment-created", consumerGroup = "tf-comment-persist")
public class CommentPersistConsumer implements RocketMQListener<CommentCreatedEvent> {

    private static final Logger log = LoggerFactory.getLogger(CommentPersistConsumer.class);

    private final CommentJdbcRepository commentRepository;
    /** 可为 null（单测直接 new 一参构造器）。 */
    private final CommentPageCache pageCache;

    /** 兼容既有调用方（含单测）：无缓存增强。 */
    public CommentPersistConsumer(CommentJdbcRepository commentRepository) {
        this(commentRepository, null);
    }

    @Autowired
    public CommentPersistConsumer(CommentJdbcRepository commentRepository, CommentPageCache pageCache) {
        this.commentRepository = commentRepository;
        this.pageCache = pageCache;
    }

    @Override
    public void onMessage(CommentCreatedEvent e) {
        // 失败日志必须带原请求 traceId，否则「评论计数 +1 但正文没进库」无法关联回哪次发布。
        Map<String, String> previous = TraceContext.begin(e == null ? null : e.getTraceId());
        boolean inserted = false;
        try {
            if (e == null) {
                throw new IllegalArgumentException("评论创建事件为空，无法落库");
            }
            inserted = commentRepository.insertIfAbsent(e.toComment());
        } catch (Exception ex) {
            log.error("评论事件落库失败（上抛触发 RocketMQ 重试 / DLQ，不 ack）: commentId={}, mediaId={}",
                    e == null ? null : e.getCommentId(), e == null ? null : e.getMediaId(), ex);
            // 包装成 unchecked 上抛：spring-rocketmq 仅在监听器抛异常时返回 RECONSUME_LATER。
            throw new IllegalStateException("评论事件落库失败，触发 MQ 重试", ex);
        } finally {
            TraceContext.end(previous);
        }
        // 失效放在 try 之外并单独兜住：缓存是旁路增强，它的任何异常都不得被当成落库失败而触发 MQ 重投。
        if (inserted && pageCache != null) {
            try {
                pageCache.evict(e.getMediaId());
            } catch (Exception ex) {
                log.warn("评论页缓存失效失败（不影响落库，不触发 MQ 重投）: mediaId={}, {}",
                        e.getMediaId(), ex.toString());
            }
        }
    }
}
