package com.turbofeed.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.FeedEngineProperties;
import com.turbofeed.gateway.service.feed.FeedDeliveryHealth;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.shared.model.ContractVersion;
import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.PlayableView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.turbofeed.gateway.security.InternalTokenInterceptor.HEADER;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private static final String PATH_PLAYABLE = "/internal/feed/playable";
    private static final String PATH_INTEREST_SEARCH = "/internal/feed/interest/search";
    private static final String PATH_DLQ_LIST = "/internal/feed/timeline/dlq";
    private static final String PATH_DLQ_REPLAY = "/internal/feed/timeline/dlq/replay";

    private final FeedEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** 投递成功/失败计数：fail-open 不能变成"无声丢失"，失败必须留下可查的痕迹。 */
    private final FeedDeliveryHealth health;
    /** 服务间内部接口共享令牌（评审 doc P0-2）；为空时不为请求附加 X-Internal-Token（fail-open 本地开发）。 */
    private final String internalToken;

    /**
     * 直连分支用 {@code directRestClientBuilder}（{@code ServiceDiscoveryRestClientConfig}
     * 显式声明的直连 builder）：它与应用共享同一套消息转换器与 {@code ObjectMapper}——这一点是必需的，
     * 契约里的 {@code createdAt} 是 {@code Instant}，只有应用级 mapper 才注册了 JSR-310 模块，
     * 否则日期字段会以时间戳数组形式写出、引擎侧解析失败。
     *
     * <p><b>为什么必须显式限定</b>：Boot 的 plain builder 因本工程自定义了
     * {@code RestClient.Builder} Bean 而 {@code @ConditionalOnMissingBean} 退避，
     * 上下文中 builder 候选只剩/仅有显式声明的两个——不加限定符会因双候选歧义启动失败，
     * 误注入 {@code @LoadBalanced} 版则直连模式被 LB 拦截
     * （{@code No instances available for host}）。</p>
     *
     * <p><b>服务发现切换</b>：当 {@code engine.serviceName} 非空时改用 {@code @LoadBalanced}
     * builder + {@code baseUrl("http://" + serviceName)}，由 Nacos 注册表解析真实实例
     * （健康检查 + 负载均衡 + 故障摘除）；为空时走直连 builder 直连 {@code baseUrl}。
     * 见 {@code ServiceDiscoveryRestClientConfig}。</p>
     */
    public FeedEngineClient(FeedEngineProperties properties,
                            ObjectMapper objectMapper,
                            @Qualifier("directRestClientBuilder") RestClient.Builder restClientBuilder,
                            @LoadBalanced RestClient.Builder lbRestClientBuilder,
                            FeedDeliveryHealth health,
                            @Value("${turbofeed.internal.token:}") String internalToken) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.health = health;
        this.internalToken = internalToken == null ? "" : internalToken;
        FeedEngineProperties.Engine engine = properties.getEngine();
        boolean useDiscovery = !engine.getServiceName().isBlank();
        RestClient.Builder chosenBuilder = useDiscovery ? lbRestClientBuilder : restClientBuilder;
        String baseUrl = useDiscovery ? "http://" + engine.getServiceName() : engine.getBaseUrl();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) engine.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) engine.getReadTimeout().toMillis());
        this.restClient = chosenBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // 服务间内部接口鉴权（评审 doc P0-2）：配置令牌时为所有 /internal/feed/** 请求加 X-Internal-Token，
                // 与引擎侧 InternalTokenInterceptor 共享同一密钥。未配置令牌则不加（fail-open 本地开发）。
                .defaultHeaders(headers -> {
                    if (!this.internalToken.isBlank()) {
                        headers.set(HEADER, this.internalToken);
                    }
                })
                .build();
        log.info("Feed 引擎客户端初始化: mode={}, baseUrl={}, connectTimeout={}, readTimeout={}, degradedMode={}, internalToken={}",
                useDiscovery ? "服务发现(Nacos)" : "直连", baseUrl, engine.getConnectTimeout(),
                engine.getReadTimeout(), properties.getDegradedMode(),
                this.internalToken.isBlank() ? "未配置(fail-open)" : "已配置");
    }

    /**
     * 读取公域推荐流（keyset 游标分页）。
     *
     * <p>返回 {@link FeedRecommendedPage}：当前页内容 + 续拉游标 {@code nextCursor} + {@code hasMore}。
     * {@code cursor} 非空时走游标分页（向引擎传 {@code cursor} 参数、不再传 {@code page}）；
     * 为空时走页码分页（传 {@code page} + {@code size}），行为与改造前一致。</p>
     *
     * @param page   页码（cursor 为空时生效，从 0 起）
     * @param size   单页条数（≤0 兜底 20）
     * @param userId 个性化用户（已登录态；匿名为 {@code null}）
     * @param cursor 续拉游标（空 = 首页，走页码分页）
     * @return {@code Optional.of(page)} = 引擎正常响应；{@link Optional#empty()} = 引擎不可用，
     *         调用方据此决定降级
     */
    public Optional<FeedRecommendedPage> recommended(int page, int size, String userId, String cursor) {
        int limit = size <= 0 ? 20 : size;
        try {
            String body = restClient.get()
                    .uri(uri -> {
                        if (cursor != null && !cursor.isBlank()) {
                            return uri.path(PATH_RECOMMENDED)
                                    .queryParam("cursor", cursor)
                                    .queryParam("size", limit)
                                    .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
                                    .build();
                        }
                        return uri.path(PATH_RECOMMENDED)
                                .queryParam("page", page)
                                .queryParam("size", limit)
                                .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
                                .build();
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = "引擎返回失败码 code=" + code + " message=" + root.path("message").asText("");
                health.recordReadFailure(msg);
                log.warn("Feed 引擎返回失败码，按不可用处理: page={}, size={}, cursor={}, {}",
                        page, limit, cursor, msg);
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.of(new FeedRecommendedPage(List.of(), "", false, 0));
            }
            // 引擎下发的是契约类型 FeedItemView；网关侧 FeedRecommendedPage.items 是 MediaItem 视图
            // （已注入计数/已赞态），两者字段口径不同，不能直接 convertValue 到 MediaItem 字段上。
            // 先按契约类型抽出原始条目列表，再做版本协商（M3）与映射。
            List<FeedItemView> rawItems = objectMapper.convertValue(data.get("items"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FeedItemView.class));
            String nextCursor = data.path("nextCursor").asText("");
            boolean hasMore = data.path("hasMore").asBoolean(false);
            // 引擎回传的绝对翻页序号（keyset 模式下由游标 depth 续传累加），与入参 page 区分命名避免遮蔽
            int returnedPage = data.path("page").asInt(0);
            // 版本协商（M3）：引擎可能已先升级并写出更高版本的条目，按错误语义渲染等于静默出错。
            // 口径与引擎侧 ItemViewCodec 一致——不可消费的条目跳过，而不是让整页失败。
            List<MediaItem> items = ContractVersion.filterConsumable(rawItems,
                    FeedItemView.SCHEMA_VERSION, FeedItemView::schemaVersion)
                    .stream().map(FeedItemMapper::toView).toList();
            return Optional.of(new FeedRecommendedPage(items, nextCursor, hasMore, returnedPage));
        } catch (Exception e) {
            health.recordReadFailure(e.toString());
            log.warn("Feed 引擎读取失败（由调用方按 degraded-mode 决定降级口径）: page={}, size={}, cursor={}, {}",
                    page, limit, cursor, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 播放前可放性复校（抖音式「非公平态 5s 刷新」）。fail-open：引擎不可用 / 非 2xx / 失败码
     * 均返回 {@link Optional#empty()}，由网关决定 fail-open 口径（默认「全部可播放」不误杀）。
     *
     * @param postIds 待复校帖子集合
     * @return {@code Optional.of(list)} = 引擎正常响应；{@code Optional.empty()} = 引擎不可用
     */
    public Optional<List<PlayableView>> playable(Set<String> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path(PATH_PLAYABLE)
                            .queryParam("postIds", String.join(",", postIds))
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                health.recordReadFailure("引擎返回失败码 code=" + code);
                log.warn("Feed 引擎 playable 返回失败码，按不可用处理: {}", code);
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.of(List.of());
            }
            List<PlayableView> items = objectMapper.convertValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlayableView.class));
            return Optional.of(items);
        } catch (Exception e) {
            health.recordReadFailure(e.toString());
            log.warn("Feed 引擎 playable 读取失败（fail-open）: {}", e.getMessage());
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
            // 引擎恢复后跑一次 /api/v1/admin/feed/timeline/backfill 即可把这段时间的存量补回来。
            log.warn("Feed 引擎入流投递失败（fail-open，不影响审核主流程）: mediaId={}, poolLevel={}, {}"
                            + " —— 该内容不会出现在发现流；引擎恢复后执行 /api/v1/admin/feed/timeline/backfill 补投",
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
    /**
     * 搜索兴趣入画像（引擎侧 {@code InterestService#accumulateSearchTags}）。
     *
     * <p><b>为什么归这个客户端</b>：画像键 {@code tf:user:interest:{userId}} 的衰减语义
     * （先整体衰减 → 再加权 → 续 TTL）是引擎用 Lua 实现并持有的。网关若直写 Redis，
     * 就等于把同一份衰减逻辑抄第二遍——两边一旦漂移，画像行为会随调用路径不同而不同，
     * 这种 bug 几乎不可能在测试里发现。所以哪怕只是加一个权重，也必须走引擎。</p>
     *
     * <p><b>fail-open 且静默</b>：画像少记一次不影响搜索结果，日志留痕即可，
     * 不返回布尔值给调用方加压（调用方本来也不会重试这种旁路信号）。</p>
     *
     * @param userId 登录用户 ID；匿名（null/空）直接跳过，画像按人归属
     * @param tags   搜索命中的话题标签
     */
    public void recordSearchInterest(String userId, List<String> tags) {
        if (userId == null || userId.isBlank() || tags == null || tags.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri(uri -> uri.path(PATH_INTEREST_SEARCH)
                            .queryParam("userId", userId)
                            .queryParam("tags", tags.toArray(new String[0]))
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 旁路信号：失败只告警，绝不回传失败给搜索链路
            log.warn("搜索兴趣入画像失败（忽略，不影响搜索结果）: userId={}, tags={}, {}",
                    userId, tags, e.getMessage());
        }
    }

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

    /**
     * 列出引擎当前死信（只读）。
     *
     * @return {@code Optional.of(list)} = 引擎正常响应；{@code Optional.empty()} =
     *         引擎不可用 / DLQ 重放功能未启用（404）。fail-open，绝不抛
     */
    public Optional<List<DlqSummaryView>> dlqList() {
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path(PATH_DLQ_LIST).build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.warn("Feed 引擎 DLQ 列表返回失败码 code={}", code);
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.of(List.of());
            }
            List<DlqSummaryView> items = objectMapper.convertValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, DlqSummaryView.class));
            return Optional.of(items);
        } catch (Exception e) {
            log.warn("Feed 引擎 DLQ 列表读取失败（功能可能未启用，按空处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 重放引擎死信（按 mediaId 精确或全量）。fail-open：引擎不可用 / 未启用返回 empty。
     *
     * @param mediaIds 仅重放这些 mediaId 对应的死信
     * @param all      为 true 时重放全部（忽略 mediaIds）
     */
    public Optional<DlqReplayResultView> dlqReplay(Set<String> mediaIds, boolean all) {
        try {
            String body = restClient.post()
                    .uri(uri -> uri.path(PATH_DLQ_REPLAY).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DlqReplayRequest(mediaIds, all))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.warn("Feed 引擎 DLQ 重放返回失败码 code={}", code);
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.convertValue(data, DlqReplayResultView.class));
        } catch (Exception e) {
            log.warn("Feed 引擎 DLQ 重放失败（功能可能未启用，按空处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
