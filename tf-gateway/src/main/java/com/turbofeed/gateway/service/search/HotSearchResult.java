package com.turbofeed.gateway.service.search;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 热搜榜返回体。
 *
 * <p>刻意不含「排名」字段：名次就是数组下标，前端自己 +1 即可。多一个冗余字段只会
 * 制造「数组顺序与 rank 字段不一致」这种低级不一致。</p>
 *
 * <p>每个字段都显式标注 {@link JsonProperty}——本项目未开启 {@code -parameters}
 * 编译参数，record 的组件名不会自动成为 JSON 字段名。</p>
 *
 * @param items 按综合热度降序的词条
 */
public record HotSearchResult(
        @JsonProperty("items") List<Item> items) {

    /**
     * 单个热搜词条。
     *
     * @param word     归一化后的词（与搜索接口的入参口径一致，可直接回填搜索框）
     * @param score    综合热度 = 搜索次数 + 点击次数 × clickWeight（<b>只用于排序</b>）
     * @param searches 窗口内搜索次数（去重后）
     * @param clicks   窗口内「点进结果」次数（去重后）
     *
     * <p><b>为什么把原始计数一起返回</b>：{@code score} 是加权和，权重随时可调，
     * 只有原始计数是可审计的事实。运营看「这个词为什么上榜」时，需要看到是搜得多还是点得多，
     * 而不是一个黑盒分数。</p>
     */
    public record Item(
            @JsonProperty("word") String word,
            @JsonProperty("score") double score,
            @JsonProperty("searches") double searches,
            @JsonProperty("clicks") double clicks) {
    }

    /** 空榜单（索引不可用 / 窗口内无数据时返回，语义是「暂时没有」而非「出错」）。 */
    public static HotSearchResult empty() {
        return new HotSearchResult(List.of());
    }
}
