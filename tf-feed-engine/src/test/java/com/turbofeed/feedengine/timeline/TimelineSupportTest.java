package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimelineSupport} 单测：时间线成员串的解析口径（M2 / 4.1 读路径版本协商）。
 *
 * <p><b>为什么单独测解析</b>：{@code parse} 是读路径上<b>唯一</b>的成员串解释点，它的一条
 * 口径决定整条时间线的容错行为。它被刻意设计成「任何异常 → {@code null}」——宁可丢一条，
 * 也不让一页 Feed 因为一个坏成员串整体失败。契约版本协商加进来后，要确认它没有把这个
 * 性质改坏：<b>不兼容版本同样表现为「跳过这一条」，而不是抛出去</b>。</p>
 */
class TimelineSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void parse_正常成员串() throws Exception {
        FeedItemView v = new FeedItemView("post/1/x", "media/1/a.jpg", "http://u", null,
                FeedItemView.STATUS_APPROVED, null, null, null, null);
        String json = MAPPER.writeValueAsString(v);

        FeedItemView back = TimelineSupport.parse(MAPPER, json);

        assertThat(back).isNotNull();
        assertThat(back.timelineKey()).isEqualTo("post/1/x");
    }

    @Test
    void parse_历史成员串无版本号_按首版容忍() {
        // 已物化在 tf:feed:tl:* 里的老格式：既没有 postId/images，也没有 schemaVersion
        String legacy = "{\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\",\"status\":\"APPROVED\","
                + "\"createdAt\":\"2026-10-26T00:00:00Z\",\"caption\":\"cap\",\"captionMark\":null}";

        FeedItemView v = TimelineSupport.parse(MAPPER, legacy);

        assertThat(v).isNotNull();
        assertThat(v.schemaVersion()).isNull();
        assertThat(v.timelineKey()).isEqualTo("media/1/a.jpg");
        assertThat(v.imageUrls()).containsExactly("http://u");
    }

    @Test
    void parse_高版本成员串_跳过该条而非整页失败() throws Exception {
        String future = "{\"postId\":\"post/1/x\",\"mediaId\":\"media/1/a.jpg\",\"url\":\"http://u\","
                + "\"status\":\"APPROVED\",\"schemaVersion\":"
                + (FeedItemView.SCHEMA_VERSION + 1) + "}";

        // 关键：返回 null（跳过这一条），绝不抛异常——读路径的 fail-open 语义必须保持
        assertThat(TimelineSupport.parse(MAPPER, future)).isNull();
    }

    @Test
    void parse_坏成员串_返回null() {
        assertThat(TimelineSupport.parse(MAPPER, "{ this is not json")).isNull();
        assertThat(TimelineSupport.parse(MAPPER, (String) null)).isNull();
    }
}
