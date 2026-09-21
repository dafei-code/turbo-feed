package com.turbofeed.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.FeedEngineProperties;
import com.turbofeed.gateway.service.feed.FeedDeliveryHealth;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.shared.model.FeedItemView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * tf-feed-engine 的 HTTP 客户端（网关 → 引擎，同步）。
 *
 * <p><b>定位</b>：本类是网关与引擎之间的<b>防腐层</b>（Anti-Corruption Layer）——
 * 对外暴露网关自己的视图模型（{@link MediaItem}），内部完成与跨服务契约
 * {@link FeedItemView} 的映射，使业务代码对"时间线已迁到独立进程"这件事几乎无感
 * （调用点只是从 {@code feedTimelineStore.xxx()} 改名为 {@code feedEngineClient.xxx()}）。</p>
 *
 * <p><b>为什么手写 JSON 解析而不是直接绑 Result</b>：{@code Result} 的 {@code isSuccess()}
 * 会被 Jackson 序列化成 {@code "success":true}，但它既无对应字段也无 setter——反序列化时
 * 该字段成为"未知属性"。Spring Boot 默认关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES} 所以生产能跑，
 * 但只要有人为严格校验打开该开关，整条发现流会静默全空（只剩一条 WARN）。这里显式用
 * {@link JsonNode} 取 {@code code}/{@code data}：既不依赖 Boot 的容错默认值，也不改动
 * 已对外发布的 {@code Result} JSON 结构。引擎返回的数据段再按契约类型转换。</p>
 *
 * <p><b>失败语义</b>：所有方法都<b>不抛异常</b>。
 * <ul>
 *   <li>读：返回 {@link Optional#empty()} 表示"引擎不可用"（与"引擎正常但没有内容"——
 *       {@code Optional.of(List.of())}——严格区分），降级口径由调用方按
 *       {@code turbofeed.feed.degraded-mode} 决定；</li>
 *   <li>写：fail-open 仅告警，不阻断审核/删除主流程（与拆分前本地写 Redis 失败的语义一致）。</li>
 * </ul>
 * 写侧当前为同步 HTTP，引擎抖动时内容会静默不入流；改经 RocketMQ 可靠投递是 B2 的内容。</p>
 *
 * <p><b>超时</b>：建连/读取超时均显式配置（见 {@link FeedEngineProperties.Engine}），
 * 避免引擎故障时网关线程池被慢调用耗尽——这是同步调用最容易被忽略的雪崩入口。</p>
 */
@Slf4j
@Component
public class FeedEngineClient {

    private static final String PATH_RECOMMENDED = "/internal/feed/recommended";
    private static final String PATH_TIMELINE_APPEND = "/internal/feed/timeline/append";
    private static final String PATH_TIMELINE_REMOVE = "/internal/feed/timeline/remove";
    private static final String PATH_BEHAVIOR = "/internal/feed/behavior";

    private final FeedEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** 投递成功/失败计数：fail-open 不能变成"无声丢失"，失败必须留下可查的痕迹。 */
    private final FeedDeliveryHealth health;

    /**
     * 使用 Spring Boot 自动装配的 {@link RestClient.Builder}（prototype 作用域，故 clone 后使用）：
     * 它与应用共享同一套消息转换器与 {@code ObjectMapper}——这一点是必需的，
     * 契约里的 {@code createdAt} 是 {@code Instant}，只有应用级 mapper 才注册了 JSR-310 模块，
     * 否则日期字段会以时间戳数组形式写出、引擎侧解析失败。
     */
    public FeedEngineClient(FeedEngineProperties properties,
                            ObjectMapper objectMapper,
                            RestClient.Builder restClientBuilder,
                            FeedDeliveryHealth health) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.health = health;
        FeedEngineProperties.Engine engine = properties.getEngine();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) engine.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) engine.getReadTimeout().toMillis());
        this.restClient = restClientBuilder.clone()
                .baseUrl(engine.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        log.info("Feed 引擎客户端初始化: baseUrl={}, connectTimeout={}, readTimeout={}, degradedMode={}",
                engine.getBaseUrl(), engine.getConnectTimeout(), engine.getReadTimeout(),
                properties.getDegradedMode());
    }

    /**
     * 读取公域推荐流。
     *
     * @return {@code Optional.of(list)} = 引擎正常响应（list 可能为空即"没有更多内容"）；
     *         {@link Optional#empty()} = 引擎不可用（网络异常 / 非 2xx / 业务失败码），
     *         调用方据此决定降级
     */
    public Optional<List<MediaItem>> recommended(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path(PATH_RECOMMENDED)
                            .queryParam("page", page)
                            .queryParam("size", limit)
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = "引擎返回失败码 code=" + code + " message=" + root.path("message").asText("");
                health.recordReadFailure(msg);
                log.warn("Feed 引擎返回失败码，按不可用处理: page={}, size={}, {}", page, limit, msg);
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.of(List.of());
            }
            List<FeedItemView> items = objectMapper.convertValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FeedItemView.class));
            return Optional.of(items.stream().map(FeedItemMapper::toView).toList());
        } catch (Exception e) {
            health.recordReadFailure(e.toString());
            log.warn("Feed 引擎读取失败（由调用方按 degraded-mode 决定降级口径）: page={}, size={}, {}",
                    page, limit, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 内容过审入流（幂等：引擎侧同一 mediaId 重复投递会先摘旧位置再写新位置）。
     * fail-open：失败仅告警，不阻断审核主流程。
     *
     * @return true = 请求送达引擎；false = 投递失败（本次内容不会进流，需事后补投）。
     *         <b>调用方请务必看这个返回值</b>——忽略它就会出现"补投报告说成功、实际一条没进"的假象。
     */
    public boolean append(MediaItem item, int poolLevel) {
        if (item == null) {
            return false;
        }
        try {
            restClient.post()
                    .uri(uri -> uri.path(PATH_TIMELINE_APPEND)
                            .queryParam("poolLevel", poolLevel)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(FeedItemMapper.toContract(item))
                    .retrieve()
                    .toBodilessEntity();
            health.recordAppendSuccess();
            return true;
        } catch (Exception e) {
            health.recordAppendFailure(e.toString());
            // 补救指引必须写在告警里：否则值班同学看到 WARN 也不知道下一步该做什么。
            // 引擎恢复后跑一次 /api/admin/feed/timeline/backfill 即可把这段时间的存量补回来。
            log.warn("Feed 引擎入流投递失败（fail-open，不影响审核主流程）: mediaId={}, poolLevel={}, {}"
                            + " —— 该内容不会出现在发现流；引擎恢复后执行 /api/admin/feed/timeline/backfill 补投",
                    item.mediaId(), poolLevel, e.getMessage());
            return false;
        }
    }

    /**
     * 内容移出公域（删除 / 下架 / 申诉中）。fail-open：失败仅告警，不阻断删除主流程。
     *
     * @return true = 请求送达引擎；false = 投递失败（该内容可能仍在发现流展示）
     */
    public boolean remove(String mediaId) {
        if (mediaId == null) {
            return false;
        }
        try {
            restClient.post()
                    .uri(uri -> uri.path(PATH_TIMELINE_REMOVE)
                            .queryParam("mediaId", mediaId)
                            .build())
                    .retrieve()
                    .toBodilessEntity();
            health.recordRemoveSuccess();
            return true;
        } catch (Exception e) {
            health.recordRemoveFailure(e.toString());
            // 下架失败比入流失败更危险：已下架内容会继续在发现流展示。
            log.warn("Feed 引擎下架投递失败（fail-open，不影响删除主流程）: mediaId={}, {}"
                            + " —— 该内容可能仍在发现流展示，请尽快确认引擎状态", mediaId, e.getMessage());
            return false;
        }
    }

    /**
     * 上报行为埋点（曝光/完播/点赞/评论/分享/不感兴趣）。fail-open：失败仅告警，
     * 埋点是推荐优化项，绝不影响审核/发布/浏览主流程。
     *
     * @param events 行为事件列表（已转成跨服务契约 {@link FeedBehaviorEvent}）
     */
    public void reportBehavior(List<FeedBehaviorEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri(PATH_BEHAVIOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(events)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("行为埋点投递引擎失败（fail-open，不影响主流程）: {}", e.getMessage());
        }
    }
}
