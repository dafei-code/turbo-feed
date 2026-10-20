package com.turbofeed.gateway.service.search;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 搜索结果页（对外契约）。
 *
 * <p><b>为什么游标而非页码</b>：与评论列表同款思路——话题下的内容持续新增，页码会随插入漂移
 * （第 2 页看到第 1 页的尾巴）。游标编码「上一页最后一条的排序位」，翻页稳定不重不漏。</p>
 *
 * @param items      本页结果（已过审核过滤，均为公域可见）
 * @param nextCursor 下一页游标；{@code ""} 表示没有下一页
 * @param hasMore    是否还有下一页
 * @param total      该话题的<b>索引总量</b>（{@code ZCARD}，O(1)）。注意这是<b>过滤前</b>的原始条数，
 *                   仅用于前端展示「约 N 条」，与 {@code items} 实际可返回条数可能不等
 */
public record SearchPage(
        @JsonProperty("items") List<Item> items,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasMore") boolean hasMore,
        @JsonProperty("total") long total) {

    /** 空页（tag 为空 / 索引冷 / Redis 不可用时的统一返回）。 */
    public static SearchPage empty() {
        return new SearchPage(List.of(), "", false, 0L);
    }

    /**
     * 单条搜索结果。
     *
     * @param postId   帖身份（{@code post/{userId}/{uuid}}；历史单图数据回退 mediaId）
     * @param mediaId  代表媒体标识（首图）
     * @param url      封面地址（{@code images[0]}）
     * @param images   帖内全部图片（按 seq 升序），保证非空
     * @param caption  文案原文
     * @param tags     该帖话题（小写、去重）
     * @param authorId 作者 userId
     */
    public record Item(
            @JsonProperty("postId") String postId,
            @JsonProperty("mediaId") String mediaId,
            @JsonProperty("url") String url,
            @JsonProperty("images") List<String> images,
            @JsonProperty("caption") String caption,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("authorId") String authorId) {
    }
}
