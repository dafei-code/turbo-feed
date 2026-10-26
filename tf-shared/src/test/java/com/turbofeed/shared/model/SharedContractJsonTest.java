package com.turbofeed.shared.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.shared.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨服务契约的 JSON 回归测试（M2 / 4.1）。
 *
 * <p>tf-shared 里的 record 就是网关 ⇄ 引擎 ⇄ 计数器的<b>协议本身</b>：字段名一改，编译照过、
 * 单测照绿，只有线上消息解析才炸——而且是「一边发新格式、一边按旧格式读」的跨版本炸法，
 * 排查时往往已经有消息堆积。所以这里把序列化结果<b>写死成 golden 串</b>，字段漂移即红。</p>
 *
 * <p><b>为什么刻意对齐 Spring Boot 的 ObjectMapper 默认配置</b>：裸 {@code new ObjectMapper()}
 * 会把 {@code Instant} 输出成小数秒时间戳（{@code 1792972800.000000000}），而线上 Boot 自动装配
 * 的 mapper 默认关掉了 {@code WRITE_DATES_AS_TIMESTAMPS}，输出 ISO-8601
 * （{@code "2026-10-26T00:00:00Z"}）。两者差着一整个时间格式——若用裸 mapper 写 golden，
 * 锁住的恰恰是<b>非生产</b>那一种，等于把错误格式固化成契约。本测试因此显式对齐 Boot 口径。</p>
 *
 * <p><b>四条护栏</b>：</p>
 * <ol>
 *   <li><b>golden 串</b>：字段名 / 顺序 / 格式写死，漂移即红；</li>
 *   <li><b>幽灵字段护栏</b>：序列化出的 key 集合必须与预期<b>完全相等</b>。拦的是本项目反复踩的
 *       {@code is} 前缀陷阱——在契约上加一个 {@code isSuccess()}，Jackson 会把它当属性写进
 *       消息体，凭空污染协议；</li>
 *   <li><b>反向兼容</b>：历史串（缺字段）必须能被反序列化，缺失字段为 {@code null}。已物化在
 *       Redis 时间线里的成员串没法回溯改格式，兼容是硬约束；</li>
 *   <li><b>反序列化机制</b>：断言 record 的（反）序列化<b>不依赖</b> {@code -parameters}
 *       编译参数——见 {@link #record不依赖编译期形参名()}。</li>
 * </ol>
 */
class SharedContractJsonTest {

    /** 与 Spring Boot 自动装配口径对齐：ISO-8601 时间、容忍未知字段。 */
    private static final ObjectMapper MAPPER = JsonMapperFactory.bootLike();

    /** 裸 mapper：连 ParameterNamesModule 都不注册，用于验证 record 不依赖 -parameters。 */
    private static final ObjectMapper BARE_MAPPER = JsonMapperFactory.bare();

    private static final Instant CREATED_AT = Instant.parse("2026-10-26T00:00:00Z");

    // ---------------------------------------------------------------- FeedItemView

    @Test
    @DisplayName("FeedItemView golden 串：字段名与时间格式不得漂移")
    void feedItemView_goldenJson() throws Exception {
        FeedItemView v = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u",
                List.of("http://u", "http://u2"), "APPROVED", CREATED_AT, "cap", "{\"@\":[]}", List.of("cat"));

        assertThat(MAPPER.writeValueAsString(v)).isEqualTo(
                "{\"postId\":\"post/1/x\",\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\","
                        + "\"images\":[\"http://u\",\"http://u2\"],\"status\":\"APPROVED\","
                        + "\"createdAt\":\"2026-10-26T00:00:00Z\",\"caption\":\"cap\","
                        + "\"captionMark\":\"{\\\"@\\\":[]}\",\"tags\":[\"cat\"],\"schemaVersion\":1}");

        FeedItemView back = MAPPER.readValue(MAPPER.writeValueAsString(v), FeedItemView.class);
        assertThat(back).isEqualTo(v);
        assertThat(back.timelineKey()).isEqualTo("post/1/x");
        assertThat(back.approved()).isTrue();
    }

    @Test
    @DisplayName("FeedItemView 不得序列化出派生方法（timelineKey / approved / imageUrls 非属性）")
    void feedItemView_无幽灵字段() throws Exception {
        FeedItemView v = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null,
                "APPROVED", CREATED_AT, null, null, null);
        assertThat(keysOf(v)).containsExactlyInAnyOrder(
                "postId", "mediaId", "url", "images", "status", "createdAt", "caption", "captionMark", "tags",
                "schemaVersion");
    }

    @Test
    @DisplayName("历史单图成员串（无 postId / images / tags）必须能反序列化，回退语义不变")
    void feedItemView_历史串反向兼容() throws Exception {
        // 已物化在 tf:feed:tl:* 里的老格式：只有后 6 个字段
        String legacy = "{\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\",\"status\":\"APPROVED\","
                + "\"createdAt\":\"2026-10-26T00:00:00Z\",\"caption\":\"cap\",\"captionMark\":null}";

        FeedItemView v = MAPPER.readValue(legacy, FeedItemView.class);

        assertThat(v.postId()).isNull();
        assertThat(v.images()).isNull();
        assertThat(v.tags()).isNull();
        assertThat(v.timelineKey()).isEqualTo("media/1/a.jpg");   // 回退用 mediaId 作帖身份
        assertThat(v.imageUrls()).containsExactly("http://u");    // 回退为单图帖
    }

    // ---------------------------------------------------------------- FeedTimelineEvent

    @Test
    @DisplayName("FeedTimelineEvent golden 串：APPEND 与 REMOVE 两种形态")
    void feedTimelineEvent_goldenJson() throws Exception {
        FeedTimelineEvent append = new FeedTimelineEvent("post/1/x", "APPEND", 1,
                new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null, "APPROVED", CREATED_AT,
                        null, null, null),
                CREATED_AT);
        assertThat(MAPPER.writeValueAsString(append)).isEqualTo(
                "{\"timelineKey\":\"post/1/x\",\"action\":\"APPEND\",\"poolLevel\":1,"
                        + "\"item\":{\"postId\":\"post/1/x\",\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\","
                        + "\"images\":null,\"status\":\"APPROVED\",\"createdAt\":\"2026-10-26T00:00:00Z\","
                        + "\"caption\":null,\"captionMark\":null,\"tags\":null,\"schemaVersion\":1},"
                        + "\"occurredAt\":\"2026-10-26T00:00:00Z\",\"schemaVersion\":1}");

        FeedTimelineEvent remove = new FeedTimelineEvent("post/1/x", "REMOVE", null, null, CREATED_AT);
        assertThat(MAPPER.writeValueAsString(remove)).isEqualTo(
                "{\"timelineKey\":\"post/1/x\",\"action\":\"REMOVE\",\"poolLevel\":null,"
                        + "\"item\":null,\"occurredAt\":\"2026-10-26T00:00:00Z\",\"schemaVersion\":1}");
        assertThat(keysOf(remove)).containsExactlyInAnyOrder(
                "timelineKey", "action", "poolLevel", "item", "occurredAt", "schemaVersion");
    }

    // ---------------------------------------------------------------- 其余契约

    @Test
    @DisplayName("FeedBehaviorEvent golden 串与旧端可选字段兼容")
    void feedBehaviorEvent_goldenJson() throws Exception {
        FeedBehaviorEvent e = new FeedBehaviorEvent("post/1/x", "LIKE", null, null, "42", "r1", 3);
        assertThat(MAPPER.writeValueAsString(e)).isEqualTo(
                "{\"timelineKey\":\"post/1/x\",\"type\":\"LIKE\",\"watchDuration\":null,"
                        + "\"mediaDuration\":null,\"userId\":\"42\",\"requestId\":\"r1\",\"position\":3,"
                        + "\"schemaVersion\":1}");

        // 旧端不带 requestId / position（position bias 校正上线前的数据）
        FeedBehaviorEvent legacy = MAPPER.readValue(
                "{\"timelineKey\":\"post/1/x\",\"type\":\"IMPRESSION\",\"watchDuration\":null,"
                        + "\"mediaDuration\":null,\"userId\":\"42\"}", FeedBehaviorEvent.class);
        assertThat(legacy.requestId()).isNull();
        assertThat(legacy.position()).isNull();
    }

    @Test
    @DisplayName("CounterIncrementEvent / CounterView / PlayableView golden 串")
    void counter与playable_goldenJson() throws Exception {
        assertThat(MAPPER.writeValueAsString(new CounterIncrementEvent("post/1/x", "LIKE", 1)))
                .isEqualTo("{\"postId\":\"post/1/x\",\"type\":\"LIKE\",\"delta\":1}");

        CounterView view = CounterView.zero("post/1/x").add(1, 2, 3, 4);
        assertThat(MAPPER.writeValueAsString(view)).isEqualTo(
                "{\"postId\":\"post/1/x\",\"likeCount\":1,\"commentCount\":2,"
                        + "\"shareCount\":3,\"commentLikeCount\":4}");

        assertThat(MAPPER.writeValueAsString(PlayableView.playable("post/1/x")))
                .isEqualTo("{\"postId\":\"post/1/x\",\"playable\":true,\"reason\":null}");
        assertThat(MAPPER.writeValueAsString(PlayableView.blocked("post/1/x", PlayableView.REASON_INTERCEPT)))
                .isEqualTo("{\"postId\":\"post/1/x\",\"playable\":false,\"reason\":\"INTERCEPT\"}");
    }

    // ---------------------------------------------------------------- 幽灵字段（Result）

    @Test
    @DisplayName("Result 不得序列化出 success 幽灵字段（is 前缀陷阱）")
    void result_无幽灵字段() throws Exception {
        Result<String> ok = Result.ok("hi");

        assertThat(keysOf(ok)).containsExactlyInAnyOrder("code", "message", "data", "timestamp");
        assertThat(ok.succeeded()).isTrue();

        // 反证：写成 isSuccess() 就一定会多吐一个 success——这是「为什么必须避开 is 前缀」
        // 的可执行证据，不是空口约定（Result 已据此改名为 succeeded()）。
        assertThat(keysOf(new IsPrefixed())).contains("success");
    }

    @Test
    @DisplayName("幽灵字段的代价：严格模式的消费方会整体解析失败（去掉即可正常）")
    void 幽灵字段在严格模式下致命() {
        ObjectMapper strict = MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String polluted = "{\"code\":0,\"message\":\"成功\",\"data\":\"hi\",\"timestamp\":1,\"success\":true}";
        String clean = "{\"code\":0,\"message\":\"成功\",\"data\":\"hi\",\"timestamp\":1}";

        assertThatThrownBy(() -> strict.readValue(polluted, ClientResult.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
        assertThatCode(() -> strict.readValue(clean, ClientResult.class)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 反序列化机制

    @Test
    @DisplayName("record 反序列化不依赖 -parameters：裸 mapper 也能完整往返")
    void record不依赖编译期形参名() throws Exception {
        // Jackson 2.12+ 通过 Class#getRecordComponents() 拿组件名，不走 ParameterNamesModule。
        // 固化该事实：即便哪天有人误关掉 tf-shared 的 -parameters，契约也不该崩。
        FeedItemView v = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null,
                "APPROVED", CREATED_AT, "cap", null, null);
        assertThat(BARE_MAPPER.readValue(BARE_MAPPER.writeValueAsString(v), FeedItemView.class))
                .isEqualTo(v);

        FeedTimelineEvent e = new FeedTimelineEvent("post/1/x", "REMOVE", null, null, CREATED_AT);
        assertThat(BARE_MAPPER.readValue(BARE_MAPPER.writeValueAsString(e), FeedTimelineEvent.class))
                .isEqualTo(e);
    }

    // ---------------------------------------------------------------- 版本协商

    @Test
    @DisplayName("存量数据（无版本号）与新版本一致时被容忍，更高版本被拒绝")
    void 版本协商的三条边界() {
        // ① 缺失：本字段引入前产生的存量消息 / 已物化的成员串，不可能回溯补写 → 必须容忍
        assertThat(ContractVersion.isConsumable(1, null)).isTrue();
        assertThatCode(() -> ContractVersion.requireConsumable("FeedTimelineEvent", 1, null))
                .doesNotThrowAnyException();
        // ② 等于当前版本：正常
        assertThat(ContractVersion.isConsumable(1, 1)).isTrue();
        // ③ 高于当前版本：生产者已升级、消费者未升 → 拒绝，绝不按当前语义硬解
        assertThat(ContractVersion.isConsumable(1, 2)).isFalse();
        assertThatThrownBy(() -> ContractVersion.requireConsumable("FeedTimelineEvent", 1, 2))
                .isInstanceOf(UnsupportedContractVersionException.class)
                .hasMessageContaining("contract=FeedTimelineEvent")
                .hasMessageContaining("seen=2")
                .hasMessageContaining("supported=1");
    }

    @Test
    @DisplayName("高版本消息能被反序列化出来，但必须被协商拒绝（不是解析失败）")
    void 高版本消息解析得出来但拒绝消费() throws Exception {
        // 关键区分：Jackson 不会因为版本号大就报解析错——它会老老实实把 schemaVersion=2
        // 读进来。若没有显式的版本协商，这条消息会被按 v1 语义静默处理掉。
        FeedTimelineEvent future = MAPPER.readValue(
                "{\"timelineKey\":\"post/1/x\",\"action\":\"REMOVE\",\"poolLevel\":null,\"item\":null,"
                        + "\"occurredAt\":\"2026-10-26T00:00:00Z\",\"schemaVersion\":2}",
                FeedTimelineEvent.class);

        assertThat(future.schemaVersion()).isEqualTo(2);
        assertThatThrownBy(() -> ContractVersion.requireConsumable(
                "FeedTimelineEvent", FeedTimelineEvent.SCHEMA_VERSION, future.schemaVersion()))
                .isInstanceOf(UnsupportedContractVersionException.class);
    }

    @Test
    @DisplayName("便捷构造器统一填当前版本，规范构造器可造历史版本数据")
    void 版本号由契约自身声明() {
        // 生产侧用便捷构造器：版本号只有一份真值，不会散到调用点
        assertThat(new FeedTimelineEvent("k", "REMOVE", null, null, CREATED_AT).schemaVersion())
                .isEqualTo(FeedTimelineEvent.SCHEMA_VERSION);
        assertThat(new FeedBehaviorEvent("k", "LIKE", null, null, "1", null, null).schemaVersion())
                .isEqualTo(FeedBehaviorEvent.SCHEMA_VERSION);
        // 规范构造器：测试兼容矩阵时需要造「旧版本 / 无版本」的数据
        assertThat(new FeedTimelineEvent("k", "REMOVE", null, null, CREATED_AT, null).schemaVersion())
                .isNull();
    }

    @Test
    @DisplayName("未知字段默认被忽略（契约演进的容忍前提）")
    void 未知字段被忽略() throws Exception {
        // 新版本往契约里加字段后，旧代码读到未知键必须容忍——否则跨版本灰度期必然炸
        FeedItemView v = MAPPER.readValue(
                "{\"postId\":\"post/1/x\",\"futureField\":\"x\"}", FeedItemView.class);
        assertThat(v.postId()).isEqualTo("post/1/x");
    }

    // ---------------------------------------------------------------- 工具与测试夹具

    private static List<String> keysOf(Object o) throws Exception {
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(o));
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 消费方视角的 Result DTO：用于演示幽灵字段在严格模式下的破坏力。 */
    private record ClientResult(int code, String message, String data, long timestamp) {
    }

    /** 反面样本：证明 {@code is} 前缀确实会被 Jackson 当属性序列化（仅测试夹具）。 */
    @SuppressWarnings("unused")
    private static class IsPrefixed {
        public boolean isSuccess() {
            return true;
        }
    }

    static final class JsonMapperFactory {
        static ObjectMapper bootLike() {
            return base()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }

        static ObjectMapper bare() {
            return base();
        }

        /** 只注册 JavaTimeModule，【不注册】ParameterNamesModule（裸 mapper 的严格形态）。 */
        private static ObjectMapper base() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }
}
