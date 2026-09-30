package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.result.Result;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.logging.BehaviorLogSink;
import com.turbofeed.feedengine.interest.TagIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feed 时间线的<b>内部接口</b>（服务间调用，非对外 API）。
 *
 * <p><b>路径约定</b>：统一挂在 {@code /internal/**} 下，与对外 API（{@code /api/**}，
 * 由 tf-gateway 承接）物理区分，便于在网关/Ingress 层直接拒绝外部对这个前缀的访问。
 * 引擎自身不引入鉴权（当前阶段信任内网），生产应配合网络隔离或服务间令牌。</p>
 *
 * <p><b>为什么读写都在这三个接口</b>：
 * <ul>
 *   <li>{@code GET /recommended} —— 网关转发对外 {@code /api/feed/recommended}，读时间线；</li>
 *   <li>{@code POST /timeline/append} —— 网关在审核通过/申诉翻案时投递，按信用池入流；</li>
 *   <li>{@code POST /timeline/remove} —— 网关在删除/下架/申诉中时投递，精确摘除。</li>
 * </ul>
 * 三个接口的入参出参全部是 tf-shared 的契约模型，引擎不感知网关内部的审核状态机。</p>
 *
 * <p><b>当前投递方式与已知取舍</b>：网关以同步 HTTP 调用本接口，失败仅记日志（fail-open）。
 * 与拆分前"网关本地写 Redis 失败仅记日志"语义等价，不构成回归；但引擎抖动时内容会
 * <b>静默不入流</b>。改为经 RocketMQ 可靠投递（含重试与幂等）是下一步（B2）的内容，
 * 届时本接口的语义不变，仅调用方由 HTTP 换为消费者。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/feed")
@RequiredArgsConstructor
public class FeedTimelineController {

    private final RecommendedFeedService recommendedFeedService;
    private final FeedTimelineStore feedTimelineStore;
    private final PostStatService postStatService;
    private final TagIndexService tagIndexService;
    private final InterestService interestService;
    private final SessionSequenceService sessionSequenceService;
    private final BehaviorLogSink behaviorLogSink;

    /**
     * 读取公域推荐流（入流时间倒序，分页）。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 单页条数（默认 20，≤0 兜底 20）
     * @return 当前页内容；空列表表示无更多内容或 Redis 不可用
     */
    @GetMapping("/recommended")
    public Result<List<FeedItemView>> recommended(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "userId", required = false) String userId) {
        return Result.ok(recommendedFeedService.recommended(page, size, userId));
    }

    /**
     * 内容过审入流（幂等：同一 mediaId 重复投递会先摘旧位置再写新位置）。
     *
     * @param poolLevel 信用等级对应的流量池层级（≤1 落 L1 小池，上限 L3 大池）
     * @param item      时间线条目（仅 {@code status=APPROVED} 会被写入，其余静默忽略）
     */
    @PostMapping("/timeline/append")
    public Result<Void> append(@RequestParam(value = "poolLevel", defaultValue = "1") int poolLevel,
                               @RequestBody FeedItemView item) {
        feedTimelineStore.append(item, poolLevel);
        // 同步维护标签反向索引（兴趣召回/画像的基础设施），fail-open：失败只告警。
        tagIndexService.index(item.timelineKey(), item.tags());
        // 内容进流的同一刻清掉推荐流缓存：否则新内容要等满 15s TTL 才对用户可见。
        // 放在 store.append 之后——先保证时间线落定，再让缓存失效（顺序颠倒会有"读到旧列表"的窗口）。
        recommendedFeedService.invalidate();
        return Result.ok();
    }

    /**
     * 内容移出公域（用户删除 / 举报下架 / 申诉中暂不可见）。
     *
     * @param mediaId 内容唯一标识；不存在时静默成功（幂等）
     */
    @PostMapping("/timeline/remove")
    public Result<Void> remove(@RequestParam("mediaId") String mediaId) {
        feedTimelineStore.remove(mediaId);
        // 同步摘除标签索引（与 append 用同一 timelineKey；见 TagIndexService 说明）。
        tagIndexService.remove(mediaId);
        // 下架比发布更需要及时：内容已从时间线摘除，缓存却还在返回它 = 已下架内容继续展示。
        recommendedFeedService.invalidate();
        return Result.ok();
    }

    /**
     * 行为埋点接收（网关同步 HTTP 兜底路径 / 测试直连）。
     *
     * <p>与 RocketMQ 消费者 {@link FeedBehaviorConsumer} 共用同一统计写入逻辑：{@code WATCH} 走
     * {@link PostStatService#recordWatch} 算完播率，其余类型走 {@link PostStatService#record}。
     * 原本缺这个接收端，导致 {@code turbofeed.mq.enabled=false}（默认）时埋点静默丢到 404——
     * 本次补齐，使默认路径也能累积完播/互动分，供流量池晋级器消费。</p>
     *
     * <p><b>⚠️ 与 {@link FeedBehaviorConsumer} 必须成对修改</b>：两条路径（HTTP 兜底 / MQ）
     * 最终都落到"统计 + 画像 + 落盘"三件事，任一侧漏改都会造成<b>埋点路径不一致</b>
     * （典型症状：默认环境有数据、开了 MQ 反而没样本）。改动埋点处理逻辑时两边都要看。</p>
     */
    @PostMapping("/behavior")
    public Result<Void> behavior(@RequestBody List<FeedBehaviorEvent> events) {
        if (events != null) {
            for (FeedBehaviorEvent e : events) {
                if (e == null || e.timelineKey() == null) {
                    continue;
                }
                if ("WATCH".equalsIgnoreCase(e.type())) {
                    postStatService.recordWatch(e.timelineKey(), e.watchDuration(), e.mediaDuration());
                } else {
                    postStatService.record(e.timelineKey(), e.type());
                }
                // 同一时刻累积兴趣画像（fail-open）：埋点带 timelineKey 不带标签，
                // InterestService 内部经 TagIndexService 反查该内容的标签再累加。
                interestService.accumulateFromEvent(e);
                // 同步记录 session 级最近互动序列（fail-open）：与画像共用同一权重口径，
                // 供精排的"跟手"信号使用；负向/曝光等不计权事件（权重=0）自动不记。
                sessionSequenceService.record(e.userId(), e.timelineKey(), interestService.eventWeight(e));
                // M0：原始明细落盘（训练样本的唯一来源）。放在统计/画像之后——
                // 前两者是线上效果依赖，落盘是离线优化依赖，绝不能反向拖住它们。
                behaviorLogSink.log(e);
            }
        }
        return Result.ok();
    }
}
