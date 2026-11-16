package com.turbofeed.gateway.service.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.Executor;

/**
 * 默认分支：进程内 interactionExecutor 异步落库（与 ② 点赞同款）。
 *
 * <p><b>PR-A 收敛</b>：与 MQ 消费端共用 {@link CommentJdbcRepository#insertIfAbsent}
 * 严格幂等路径；落库失败从 WARN 升级为 ERROR——评论列表直接读 DB，正文缺行是
 * 用户可见的数据丢失（列表缺条、Redis 总数与列表对不上、回复父评论 404），
 * 不能再按普通 fail-open 降级静默带过。</p>
 *
 * <p><b>优化项④ 接线</b>：只有真正插入成功才推进 {@link CommentPageCache} 的 generation。
 * 失效必须发生在「DB 已有新行」之后，否则失效后的第一次读会把缺新评论的旧列表重新
 * 回填进缓存，反而把不一致冻住一个 TTL。缓存默认关闭时 evict 是 no-op。</p>
 *
 * <p>非 {@code @Component}：Bean 由 {@code CommentPublisherConfig.LocalBranch} 在
 * {@code turbofeed.mq.enabled=false/缺省} 时声明，与 {@link RocketMqCommentEventPublisher} 互斥。</p>
 */
public class LocalCommentEventPublisher implements CommentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalCommentEventPublisher.class);

    private final CommentJdbcRepository commentRepository;
    private final Executor interactionExecutor;
    /** 可为 null（裁剪部署 / 单测直接 new 两参构造器）。 */
    private final CommentPageCache pageCache;

    /** 兼容既有调用方（含单测）：无缓存增强。 */
    public LocalCommentEventPublisher(CommentJdbcRepository commentRepository,
                                      @Qualifier("interactionExecutor") Executor interactionExecutor) {
        this(commentRepository, interactionExecutor, null);
    }

    public LocalCommentEventPublisher(CommentJdbcRepository commentRepository,
                                      @Qualifier("interactionExecutor") Executor interactionExecutor,
                                      CommentPageCache pageCache) {
        this.commentRepository = commentRepository;
        this.interactionExecutor = interactionExecutor;
        this.pageCache = pageCache;
    }

    @Override
    public void publish(Comment c) {
        interactionExecutor.execute(() -> {
            try {
                boolean inserted = commentRepository.insertIfAbsent(c);
                if (!inserted) {
                    log.warn("评论异步落库跳过（commentId 已存在，重复投递）: mediaId={}, commentId={}",
                            c.mediaId(), c.commentId());
                } else {
                    evictPageCache(c.mediaId());
                }
            } catch (Exception ex) {
                log.error("评论异步落库失败（正文未进库，评论列表将静默缺条，需对账补偿）: mediaId={}, commentId={}",
                        c.mediaId(), c.commentId(), ex);
            }
        });
    }

    /** 失效失败只由缓存组件内部 WARN，绝不反向影响已经成功的落库。 */
    private void evictPageCache(String mediaId) {
        if (pageCache != null) {
            pageCache.evict(mediaId);
        }
    }
}
