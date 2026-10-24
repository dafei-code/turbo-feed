package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.TagIndexService;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Feed 时间线投递的<b>唯一副作用入口</b>（R7：双投递通道副作用收敛）。
 *
 * <p><b>一次投递 = 三件必须一起发生的事</b>：
 * <ol>
 *   <li><b>时间线写入 / 摘除</b>（{@link FeedTimelineStore}）——主操作；</li>
 *   <li><b>标签反向索引维护</b>（{@link TagIndexService}）——兴趣召回与搜索的基础设施；</li>
 *   <li><b>推荐流缓存失效</b>（{@link RecommendedFeedService#invalidate()}）——否则新内容要等满 TTL
 *       （匿名 15s / 登录 5s）才对用户可见。</li>
 * </ol>
 * 改造前这三条<b>散落在两个投递通道里各写一遍</b>：HTTP 通道（{@code FeedTimelineController}）写了三条，
 * MQ 通道（{@code FeedTimelineConsumer}）只写了前两条——<b>漏了第 ③ 条</b>。也就是说一旦开启 MQ，
 * 新发布的内容在缓存 TTL 内对用户不可见（"我发了却看不到"），而下架的内容仍会被缓存继续返回
 * （已下架内容继续展示）。这类缺陷的根因不是某处漏写，而是<b>一致性靠注释维系</b>：注释写着
 * "两条路径必须各自显式调用"，但没有任何机制阻止漏调。现在两个门面都只解析入参、然后调本类，
 * 结构上无法漏。</p>
 *
 * <p><b>为什么不放在 FeedTimelineStore 里</b>：store 需要 {@link RecommendedFeedService} 才能做第 ③ 条，
 * 而 {@code RecommendedFeedService} 反过来依赖 store 做读取——放进 store 会形成<b>构造器循环依赖</b>。
 * 因此收敛到本类这个应用层门面：它依赖三者，三者互不知晓。</p>
 *
 * <p><b>两条通道的语义差异（通道决定传输，本类决定语义）</b>：
 * <ul>
 *   <li>{@link #apply} —— HTTP 兜底通道：异常吞掉只告警（{@code fail-open}），与改造前一致，
 *       网关侧不因引擎抖动而失败；</li>
 *   <li>{@link #applyStrict} —— MQ 顺序消息通道：异常<b>上抛</b>，交给 RocketMQ 重试 / DLQ。
 *       若这里吞掉异常，重试与 DLQ 永不触发，"静默丢失"只是从 HTTP 搬到 MQ（等于白做 B2）。</li>
 * </ul>
 * 两法的内部顺序与副作用集合<b>完全相同</b>，区别只在异常是否冒泡。
 * 这样"通道决定传输、本类决定语义"，而不是每个通道各自复制一份语义。</p>
 *
 * <p><b>未知 action 绝不按 REMOVE 兜底</b>：坏消息若被当成下架，会把正常内容摘掉，是反方向的安全事故。
 * 两种通道都<b>只告警 / 抛异常、不改动任何数据</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineDeliveryService {

    private final FeedTimelineStore feedTimelineStore;
    private final TagIndexService tagIndexService;
    private final RecommendedFeedService recommendedFeedService;

    /**
     * 投递一条时间线变更（HTTP 兜底通道，fail-open）。
     *
     * <p>主操作的失败由 {@link FeedTimelineStore#append} / {@code remove} 自身吞掉（内部 warn），
     * 因此本方法几乎不抛异常；未知 action 只记 error 并忽略——HTTP 通道本是 best-effort，
     * 但要保证"绝不误判成下架"。</p>
     */
    public void apply(FeedTimelineEvent event) {
        try {
            deliver(event, false);
        } catch (IllegalArgumentException e) {
            // 未知 action：不改动任何数据，只告警（与 strict 通道同一原则：绝不按 REMOVE 兜底）
            log.error("时间线投递被拒（未知 action，未改动任何数据）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("时间线投递失败（fail-open，HTTP 兜底通道仅告警）: timelineKey={}, action={}, {}",
                    event == null ? null : event.timelineKey(),
                    event == null ? null : event.action(), e.getMessage());
        }
    }

    /**
     * 投递一条时间线变更（MQ 顺序消息通道，严格）。
     *
     * <p>异常<b>原样上抛</b>，由消费者交给 RocketMQ 重试 / DLQ。未知 action 抛
     * {@link IllegalArgumentException}，同样不改动任何数据。</p>
     */
    public void applyStrict(FeedTimelineEvent event) {
        deliver(event, true);
    }

    /**
     * 统一的副作用序列。
     *
     * <p><b>顺序不可调换</b>：先让时间线落定（主操作），再维护标签索引（派生数据），最后失效缓存
     * （让新状态立刻可见）。若把缓存失效提到写入之前，会存在"缓存已失效、时间线还没写"的窗口，
     * 该窗口内读到的仍是旧列表。</p>
     */
    private void deliver(FeedTimelineEvent event, boolean strict) {
        if (event == null) {
            throw new IllegalArgumentException("时间线事件为 null");
        }
        if (FeedTimelineEvent.ACTION_APPEND.equals(event.action())) {
            FeedItemView item = event.item();
            if (item == null) {
                throw new IllegalArgumentException("APPEND 事件缺少 item: timelineKey=" + event.timelineKey());
            }
            int pool = event.poolLevel() == null ? 1 : event.poolLevel();
            if (strict) {
                feedTimelineStore.appendStrict(item, pool);
            } else {
                feedTimelineStore.append(item, pool);
            }
            tagIndexService.index(item.timelineKey(), item.tags());
        } else if (FeedTimelineEvent.ACTION_REMOVE.equals(event.action())) {
            String key = event.timelineKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("REMOVE 事件缺少 timelineKey");
            }
            if (strict) {
                feedTimelineStore.removeStrict(key);
            } else {
                feedTimelineStore.remove(key);
            }
            tagIndexService.remove(key);
        } else {
            // 未知 action（版本不一致 / 消息损坏）：绝不按 REMOVE 兜底——那会让一条坏消息把内容下架
            throw new IllegalArgumentException("未知的 Feed 时间线 action（拒绝按 REMOVE 兜底）: action="
                    + event.action() + ", timelineKey=" + event.timelineKey());
        }
        // 第 ③ 条副作用：内容进流/下架的同一刻清掉推荐流缓存。
        // 改造前只有 HTTP 通道做了这一步，MQ 通道漏了——开 MQ 后新内容要等满 TTL 才可见。
        recommendedFeedService.invalidate();
    }
}
