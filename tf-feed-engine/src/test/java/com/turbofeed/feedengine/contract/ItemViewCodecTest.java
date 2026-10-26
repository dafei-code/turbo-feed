package com.turbofeed.feedengine.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ItemViewCodec} 单测：{@code FeedItemView} 唯一解析入口的口径（M3 契约旁路收口）。
 *
 * <p><b>为什么单独测解析</b>：它是读路径上<b>所有</b>条目串的解释点，一条口径决定整条推荐流
 * 的容错行为。它被刻意设计成「任何异常 → 跳过」——宁可少一条内容，也不让一页 Feed 因为一个
 * 坏成员串整体失败。M2/4.1 加进来的版本协商不能改坏这个性质：<b>版本不兼容同样表现为
 * 「跳过这一条」，而不是抛出去</b>。</p>
 *
 * <p><b>M3 新增集合形态用例</b>：推荐流缓存是一整个 JSON 数组一次性反序列化出来的，没有逐条
 * try/catch 的时机，只能事后按版本号过滤。要确认它不会因为「数组里有一条高版本」而整批失败。</p>
 */
class ItemViewCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void parse_正常成员串() throws Exception {
        FeedItemView v = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null,
                FeedItemView.STATUS_APPROVED, null, null, null, null);
        String json = MAPPER.writeValueAsString(v);

        FeedItemView back = ItemViewCodec.parse(MAPPER, json);

        assertThat(back).isNotNull();
        assertThat(back.timelineKey()).isEqualTo("post/1/x");
    }

    @Test
    void parse_历史成员串无版本号_按首版容忍() {
        // 已物化在 tf:feed:tl:* 里的老格式：既没有 postId/images，也没有 schemaVersion
        String legacy = "{\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\",\"status\":\"APPROVED\","
                + "\"createdAt\":\"2026-10-26T00:00:00Z\",\"caption\":\"cap\",\"captionMark\":null}";

        FeedItemView v = ItemViewCodec.parse(MAPPER, legacy);

        assertThat(v).isNotNull();
        assertThat(v.schemaVersion()).isNull();
        assertThat(v.timelineKey()).isEqualTo("media/1/a.jpg");
        assertThat(v.imageUrls()).containsExactly("http://u");
    }

    @Test
    void parse_高版本成员串_跳过该条而非整页失败() {
        String future = "{\"postId\":\"post/1/x\",\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\","
                + "\"status\":\"APPROVED\",\"schemaVersion\":"
                + (FeedItemView.SCHEMA_VERSION + 1) + "}";

        // 关键：返回 null（跳过这一条），绝不抛异常——读路径的 fail-open 语义必须保持
        assertThat(ItemViewCodec.parse(MAPPER, future)).isNull();
    }

    @Test
    void parse_坏成员串_返回null() {
        assertThat(ItemViewCodec.parse(MAPPER, "{ this is not json")).isNull();
        assertThat(ItemViewCodec.parse(MAPPER, (String) null)).isNull();
    }

    // ===================== 集合形态（推荐流缓存）：M3 新增 =====================

    @Test
    void parseList_混入高版本条目_只剔除该条而不整批失败() throws Exception {
        FeedItemView ok = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null,
                FeedItemView.STATUS_APPROVED, null, null, null, null);
        String json = "[{\"postId\":\"post/9/y\",\"mediaId\":\"media/9/b.jpg\",\"url\":\"http://u\","
                + "\"status\":\"APPROVED\",\"schemaVersion\":" + (FeedItemView.SCHEMA_VERSION + 1) + "},"
                + MAPPER.writeValueAsString(ok) + "]";

        List<FeedItemView> items = ItemViewCodec.parseList(MAPPER, json);

        // 高版本那条被剔除，其余照常返回——绝不能因为一条不可消费就让整页回落到实时读
        assertThat(items).hasSize(1);
        assertThat(items.get(0).timelineKey()).isEqualTo("post/1/x");
    }

    @Test
    void parseList_历史条目无版本号_全部保留() {
        String legacy = "[{\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\",\"status\":\"APPROVED\"},"
                + "{\"mediaId\":\"media/2/b.jpg\",\"url\":\"http://v\",\"status\":\"APPROVED\"}]";

        List<FeedItemView> items = ItemViewCodec.parseList(MAPPER, legacy);

        assertThat(items).hasSize(2);
    }

    @Test
    void parseList_整串不可解析或为null_返回空列表而非抛异常() {
        assertThat(ItemViewCodec.parseList(MAPPER, "{ this is not json")).isEmpty();
        assertThat(ItemViewCodec.parseList(MAPPER, (String) null)).isEmpty();
    }
}
