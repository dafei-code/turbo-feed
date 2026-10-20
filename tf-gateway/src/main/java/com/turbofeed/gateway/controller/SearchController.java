package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.search.HotSearchResult;
import com.turbofeed.gateway.service.search.HotSearchService;
import com.turbofeed.gateway.service.search.SearchPage;
import com.turbofeed.gateway.service.search.SearchService;
import com.turbofeed.gateway.service.search.SuggestResult;
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
 * <p><b>四个端点</b>（S1–S4）：{@code /tag} 话题搜索、{@code /user} 用户搜索、
 * {@code /suggest} 前缀联想、{@code /hot} 热搜榜。前三个读索引，第四个读统计。</p>
 *
 * <p><b>热搜埋点为什么放在 Controller 而不是 Service</b>：计入与否取决于「这一页到底有没有结果」
 * ——那是接口层的判定（{@code total > 0}）。若下沉进 {@link SearchService}，搜索服务就得同时
 * 承担「读索引」和「记统计」两种职责，且单测要为一个旁路统计额外 mock 一套 Redis 键。
 * 埋点失败在这里是纯 fail-open（{@link HotSearchService} 内部吞异常），不影响返回。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final HotSearchService hotSearchService;

    public SearchController(SearchService searchService, HotSearchService hotSearchService) {
        this.searchService = searchService;
        this.hotSearchService = hotSearchService;
    }

    /**
     * 按话题搜索内容（时间倒序，游标翻页）。
     *
     * <p>搜索<b>有结果</b>时计入热搜（同人同词 60s 内只计一次）。搜不到结果的词不进榜：
     * 榜单上的词点进去必须是满的，否则等于把用户往空页带。</p>
     *
     * @param tag    话题词（可带 {@code #}，大小写不敏感）；空则返回空页
     * @param size   本页条数（默认 20，上限 50）
     * @param cursor 上一页返回的 {@code nextCursor}；首页留空
     */
    @GetMapping("/tag")
    public Result<SearchPage> byTag(@RequestParam(value = "tag", defaultValue = "") String tag,
                                    @RequestParam(value = "size", defaultValue = "20") int size,
                                    @RequestParam(value = "cursor", defaultValue = "") String cursor) {
        SearchPage page = searchService.searchByTag(tag, size, cursor);
        // 只在首页计一次：翻页不是一次新的搜索行为，否则深度翻页会把同一个词刷上去
        if (page.total() > 0 && (cursor == null || cursor.isBlank())) {
            hotSearchService.record(currentUserKey(), tag);
        }
        return Result.ok(page);
    }

    /**
     * 按昵称搜索用户（S2）。归一化后精确匹配，不分页（同名词条量级极小）。
     *
     * @param q    昵称（大小写不敏感）
     * @param size 最多返回条数（默认 20，上限 50）
     */
    @GetMapping("/user")
    public Result<SearchPage.UserPage> byUser(@RequestParam(value = "q", defaultValue = "") String q,
                                              @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(searchService.searchUser(q, size));
    }

    /**
     * 搜索联想（S3）：按前缀返回已存在的话题 / 用户。
     *
     * @param q    前缀（可带 {@code #}）
     * @param size 最多返回条数（默认 10，上限 20）
     */
    @GetMapping("/suggest")
    public Result<SuggestResult> suggest(@RequestParam(value = "q", defaultValue = "") String q,
                                         @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.ok(searchService.suggest(q, size));
    }

    /**
     * 热搜榜（S4）：最近 7 天合并，按累计搜索次数降序。
     *
     * <p>匿名可读。榜单为空是正常状态（新部署 / 窗口内无搜索），不是错误。</p>
     *
     * @param limit 条数（默认 10，上限 50）
     */
    @GetMapping("/hot")
    public Result<HotSearchResult> hot(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return Result.ok(hotSearchService.top(limit));
    }

    /**
     * 当前用户标识；匿名返回 {@code null}（热搜去重键里落成同一桶）。
     *
     * <p>不用 {@code requireUserId()}：搜索允许匿名，那里会直接抛「请先登录」。</p>
     */
    private String currentUserKey() {
        return UserContextHolder.get() == null ? null : UserContextHolder.get().userId();
    }
}
