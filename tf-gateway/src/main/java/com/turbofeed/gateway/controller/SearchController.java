package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.search.SearchPage;
import com.turbofeed.gateway.service.search.SearchService;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索接口（对外 {@code /api/**}）。
 *
 * <p><b>鉴权</b>：匿名可读——与评论列表同款。搜索是浏览行为的前置，强制登录会挡住内容发现；
 * 但结果<b>必然经过审核过滤</b>（见 {@link SearchService}），不存在「匿名就能搜到违规内容」的路径。</p>
 *
 * <p><b>S1 范围</b>：只做话题搜索（{@code #tag}）。用户搜索、联想补全、热搜榜分别在 S2–S4。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 按话题搜索内容（时间倒序，游标翻页）。
     *
     * @param tag    话题词（可带 {@code #}，大小写不敏感）；空则返回空页
     * @param size   本页条数（默认 20，上限 50）
     * @param cursor 上一页返回的 {@code nextCursor}；首页留空
     */
    @GetMapping("/tag")
    public Result<SearchPage> byTag(@RequestParam(value = "tag", defaultValue = "") String tag,
                                    @RequestParam(value = "size", defaultValue = "20") int size,
                                    @RequestParam(value = "cursor", defaultValue = "") String cursor) {
        return Result.ok(searchService.searchByTag(tag, size, cursor));
    }
}
