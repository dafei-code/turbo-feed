package com.turbofeed.gateway.service.search;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 搜索联想结果（S3）。
 *
 * <p><b>为什么不用表、用 score 全 0 的 ZSET</b>：{@code ZRANGEBYLEX} 只在成员 score 全相同时
 * 才按字典序工作——词典键 {@code tf:search:lex} 恒写 0，于是「按前缀取一段」是一次
 * O(log N + M) 的范围取，比 {@code LIKE 'xx%'} 打冷补表更快，也免了 S3 建表的 DDL 与
 * ShardingSphere 元数据重启。<b>冷补表只在「Redis 7d/词典丢失后要恢复全量」时才需要</b>，
 * 属 S3 第二段。</p>
 *
 * @param items 联想词（按字典序，最多 {@code size} 条）
 */
public record SuggestResult(@JsonProperty("items") List<Item> items) {

    /**
     * 单条联想。
     *
     * @param type 类型：{@code tag}（话题）/ {@code user}（用户）
     * @param text 文本（话题不含 {@code #}；用户为归一化后的昵称）
     */
    public record Item(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text) {
    }

    public static SuggestResult empty() {
        return new SuggestResult(List.of());
    }
}
