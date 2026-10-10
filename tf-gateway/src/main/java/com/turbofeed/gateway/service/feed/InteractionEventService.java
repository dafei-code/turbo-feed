package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.InteractionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 交互行为事件服务（A1 真双塔召回的训练样本底座）。
 *
 * <p>在两类发射点调用：① 服务端曝光——{@link com.turbofeed.gateway.service.query.MediaQueryService}
 * 组推荐流返回时，对每条内容异步记一次 IMPRESSION（最可靠、不依赖客户端）；② 客户端互动——
 * {@link com.turbofeed.gateway.controller.FeedBehaviorController#report} 上报点击/完播/点赞/评论/分享/
 * 不感兴趣时记对应事件。</p>
 *
 * <p><b>fail-open</b>：catch 后仅记日志、绝不抛异常、不阻断 feed / 互动主流程——交互事件是
 * 旁路训练数据，丢失不影响推荐正确性（召回/排序由既有规则 + Redis KV 保障）。</p>
 *
 * <p><b>异步</b>：方法经 {@code @Async("interactionExecutor")} 在独立执行器落库，调用方 fire-and-forget，
 * 不增加推荐流/互动接口的 P99 延迟。开关由 {@link MediaProperties.InteractionEvent#isEnabled()}
 * 控制（默认 true）；置 false 可一键停用写入（排障用）。</p>
 */
@Service
public class InteractionEventService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(InteractionEventService.class);

    private final InteractionEventRepository repository;
    private final MediaProperties mediaProperties;

    public InteractionEventService(InteractionEventRepository repository, MediaProperties mediaProperties) {
        this.repository = repository;
        this.mediaProperties = mediaProperties;
    }

    /**
     * 记录一次曝光（服务端在推荐流组页返回时触发，最可靠、不依赖客户端）。
     *
     * <p>匿名用户（userId 为空）跳过——个性化训练样本需绑定用户。
     * {@code @Async} 经 interactionExecutor 异步落库，fail-open：异常仅告警，绝不阻塞 feed 主流程。</p>
     *
     * @param userId   已登录用户（来自 JWT）；匿名为 null，跳过
     * @param itemId   内容主键（MediaItem#timelineKey()）
     * @param page     页码
     * @param position 该条目在本次结果里的位次
     */
    @Async("interactionExecutor")
    public void recordImpression(String userId, String itemId, int page, int position) {
        if (userId == null || userId.isBlank() || itemId == null || itemId.isBlank()) {
            return;
        }
        if (!mediaProperties.getInteractionEvent().isEnabled()) {
            return;
        }
        try {
            repository.insert(Long.parseLong(userId), itemId, "IMPRESSION", position, page, null, null, null, null);
        } catch (Exception e) {
            log.warn("写交互事件失败(impression, fail-open): userId={}, itemId={}, {}", userId, itemId, e.getMessage());
        }
    }

    /**
     * 记录一次客户端互动（点击/完播/点赞/评论/分享/不感兴趣）。
     *
     * <p>经 {@code /api/feed/behavior} 上报，{@code @Async} 异步落库，fail-open。</p>
     *
     * @param userId     已登录用户
     * @param itemId     内容主键（BehaviorReport#postId）
     * @param eventType  事件类型（与 BehaviorReport#type 对齐）
     * @param position   位次（可空）
     * @param page       页码（可空）
     * @param channel    召回通道（可空，引擎侧补全）
     * @param pool       流量池层级（可空）
     * @param requestId  本次列表请求 ID（可空，归组用）
     */
    @Async("interactionExecutor")
    public void record(String userId, String itemId, String eventType, Integer position,
                       Integer page, String channel, Integer pool, String requestId) {
        if (userId == null || userId.isBlank() || itemId == null || itemId.isBlank() || eventType == null) {
            return;
        }
        if (!mediaProperties.getInteractionEvent().isEnabled()) {
            return;
        }
        try {
            repository.insert(Long.parseLong(userId), itemId, eventType, position, page, channel, pool, requestId, null);
        } catch (Exception e) {
            log.warn("写交互事件失败({}, fail-open): userId={}, itemId={}, {}", eventType, userId, itemId, e.getMessage());
        }
    }
}
