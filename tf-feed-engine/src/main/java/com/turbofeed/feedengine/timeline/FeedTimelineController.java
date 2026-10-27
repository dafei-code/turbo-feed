package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import com.turbofeed.shared.model.PlayableView;
import com.turbofeed.shared.result.Result;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.logging.BehaviorLogSink;
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
    /** 时间线投递的唯一入口（R7）：本类只解析入参，副作用全部由它承担。 */
    private final FeedTimelineDeliveryService feedTimelineDelivery;
    private final PostStatService postStatService;
    private final InterestService interestService;
    private final SessionSequenceService sessionSequenceService;
    private final BehaviorLogSink behaviorLogSink;
    private final PlayabilityService playabilityService;

    /**
     * 读取公域推荐流（入流时间倒序，keyset 游标分页）。
     *
     * <p>{@code cursor} 非空时走 keyset 游标分页（以游标 score 为排他下界 seek，无 OFFSET）；
     * 为空时退化为 {@code page} 页码分页，行为与改造前一致。两者都返回
     * {@link FeedRecommendedPage}（含 {@code nextCursor} / {@code hasMore}）。</p>
     *
     * @param page   页码（cursor 为空时生效，从 0 开始，默认 0）
     * @param size   单页条数（默认 20，≤0 兜底 20）
     * @param userId 个性化用户（已登录；匿名为空）
     * @param cursor 续拉游标（空 = 首页）
     * @return 当前页内容 + 续拉游标；空列表表示无更多内容或 Redis 不可用
     */
    @GetMapping("/recommended")
    public Result<FeedRecommendedPage> recommended(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "cursor", defaultValue = "") String cursor) {
        FeedCursor decoded = (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);
        int limit = size <= 0 ? 20 : size;
        if (decoded == null) {
            return Result.ok(recommendedFeedService.recommendedPage(page, size, userId, null));
        }
        return Result.ok(recommendedFeedService.recommendedPage(0, limit, userId, decoded));
    }

    /**
     * 播放前可放性复校（抖音式「非公平态 5s 刷新」）。
     *
     * <p>召回阶段已过滤 INTERCEPT/MONITOR 与低健康分/封禁作者，但那是「预取时刻」结论；用户滑到
     * 播放的那一刻内容可能已被异步审核 / 举报复审 / 晋级调度翻成下架。本接口在「播放前」再复校一次，
     * 命中不可见则客户端渲染占位 + 5s 跳下一条（不发 WATCH/曝光/点赞以免污染推荐模型）。
     * 复用 {@link PlayabilityService}（{@code ReviewSignalClient} 直读 {@code tf:mod:} KV），fail-open。</p>
     *
     * @param postIds 待复校帖子（通常 1~3 条：当前条 + 预查的下一条），逗号分隔
     * @return 每条 postId 的复校结论（playable + reason）
     */
    @GetMapping("/playable")
    public Result<List<PlayableView>> playable(
            @RequestParam("postIds") List<String> postIds) {
        return Result.ok(playabilityService.check(postIds));
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
        // R7：三件副作用（时间线写入 + 标签索引 + 缓存失效）统一由投递入口承担，
        // 本类不再逐个调用——否则新增通道时漏掉任何一条都是静默失效。
        feedTimelineDelivery.apply(FeedTimelineEvent.append(item, poolLevel));
        return Result.ok();
    }

    /**
     * 内容移出公域（用户删除 / 举报下架 / 申诉中暂不可见）。
     *
     * @param mediaId 内容唯一标识；不存在时静默成功（幂等）
     */
    @PostMapping("/timeline/remove")
    public Result<Void> remove(@RequestParam("mediaId") String mediaId) {
        // 同上：副作用集合由投递入口统一保证（含"下架后缓存必须立刻失效"这条最容易漏的）。
        feedTimelineDelivery.apply(FeedTimelineEvent.remove(mediaId));
        return Result.ok();
    }

    /**
     * 搜索兴趣入画像：把「用户搜了什么」累进 {@code tf:user:interest:{userId}}。
     *
     * <p><b>为什么单独开一个接口，而不是并入 {@code /behavior}</b>：行为事件的模型是
     * 「谁对哪条内容做了什么」，必须有 {@code timelineKey}；而搜索发生在内容之前，
     * 此刻没有任何内容身份。硬塞进行为链路就得伪造一个事件，把「内容互动」和「查询意图」
     * 两种语义搅在一起。显式意图值得有自己的通道。</p>
     *
     * <p><b>fail-open</b>：画像写入失败只影响个性化，绝不能让搜索接口报错——
     * 用户搜东西是要拿到结果的，不是来帮我们写画像的。</p>
     *
     * @param userId 用户 ID（网关由登录态注入；匿名搜索不调用本接口）
     * @param tags   搜索命中的话题标签（逗号分隔，已归一化）
     */
    @PostMapping("/interest/search")
    public Result<Void> searchInterest(@RequestParam("userId") String userId,
                                       @RequestParam("tags") List<String> tags) {
        interestService.accumulateSearchTags(userId, tags);
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
                } else if ("UNLIKE".equalsIgnoreCase(e.type())) {
                    // 取消点赞：实时分 −1 + 热点榜按 LIKE 负权重回退（抖音式赛马回退）
                    postStatService.cancel(e.timelineKey(), e.type());
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
