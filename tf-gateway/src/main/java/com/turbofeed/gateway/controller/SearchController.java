package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.search.HotSearchResult;
import com.turbofeed.gateway.service.search.HotSearchService;
import com.turbofeed.gateway.service.search.SearchPage;
import com.turbofeed.gateway.service.search.SearchService;
import com.turbofeed.gateway.service.search.SuggestResult;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索接口（对外 {@code /api/**}）。
 *
 * <p><b>鉴权</b>：匿名可读/可上报——与评论列表同款。搜索是浏览行为的前置，强制登录会挡住内容发现；
 * 但结果<b>必然经过审核过滤</b>（见 {@link SearchService}），不存在「匿名就能搜到违规内容」的路径。</p>
 *
 * <p><b>五个端点</b>（S1–S4）：{@code /tag} 话题搜索、{@code /user} 用户搜索、
 * {@code /suggest} 前缀联想、{@code /hot} 热搜榜、{@code /hit} 搜索结果点击上报。
 * 前三个读索引，后两个是统计链路。</p>
 *
 * <p><b>埋点为什么放在 Controller 而不是 Service</b>：计入与否取决于「这一页到底有没有结果」
 * ——那是接口层的判定（{@code total > 0}）。若下沉进 {@link SearchService}，搜索服务就得同时
 * 承担「读索引」和「记统计」两种职责，且单测要为一个旁路统计额外 mock 一套 Redis 键。
 * 埋点失败在这里是纯 fail-open，不影响返回。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final HotSearchService hotSearchService;
    private final FeedEngineClient feedEngineClient;

    public SearchController(SearchService searchService,
                            HotSearchService hotSearchService,
                            FeedEngineClient feedEngineClient) {
        this.searchService = searchService;
        this.hotSearchService = hotSearchService;
        this.feedEngineClient = feedEngineClient;
    }

    /**
     * 按话题搜索内容（时间倒序，游标翻页）。
     *
     * <p>副产物两件，都是 fail-open 的旁路统计：<br>
     * ① <b>热搜</b>：搜索<b>有结果</b>时计入（同人同词 60s 内只计一次）。搜不到结果的词不进榜：
     * 榜单上的词点进去必须是满的，否则等于把用户往空页带。<br>
     * ② <b>兴趣画像</b>：登录用户搜中的话题，按 {@code search-weight} 累进
     * {@code tf:user:interest:{userId}}。搜索是推荐系统里少数几个<b>显式意图</b>信号——
     * 用户敲下"篮球"是直接告诉你他要什么，比"刷到并看了一半"的隐式推断强得多，
     * 不接这一路就等于把用户主动表达的興趣丢掉。</p>
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
        boolean firstPage = cursor == null || cursor.isBlank();
        if (page.total() > 0 && firstPage) {
            String userId = currentUserId();
            hotSearchService.record(userId, tag);
            // 画像按人归属：匿名搜索没有 userId，无画像可写，直接跳过
            if (userId != null) {
                feedEngineClient.recordSearchInterest(userId, List.of(SearchService.normalizeTag(tag)));
            }
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
     * 热搜榜（S4）：最近 7 天合并，按「搜索次数 + 点击次数 × 权重」降序。
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
     * 搜索结果点击上报（S4）：客户端在用户从搜索结果页点进内容时调用。
     *
     * <p><b>为什么必须走接口而不是客户端直接写 Redis</b>：① 需要服务端校验这个词
     * {@link SearchService#tagExists(String)} 真的有内容，否则任何人都能凭空造词刷榜；
     * ② 需要服务端做同人同词去重；③ Redis 不该对客户端可写。</p>
     *
     * <p><b>这里刻意不再写一次兴趣画像</b>：点进结果之后的观看/点赞/评论本来就有
     * WATCH / LIKE 埋点，会带着该内容的真实标签进画像。再补一路等于同一个行为记两次。</p>
     *
     * <p><b>幂等与容错</b>：重复上报被 60s 去重吃掉；任何异常都在
     * {@link HotSearchService} 内部吞掉，本接口恒返回成功——统计上报失败不该让用户看到报错。</p>
     *
     * @param tag 被点击的搜索词（可带 {@code #}，大小写不敏感）
     */
    @PostMapping("/hit")
    public Result<Void> hit(@RequestParam(value = "tag", defaultValue = "") String tag) {
        // 准入：词必须真的有内容。这是点击上报唯一的真实性保障，不能省
        if (searchService.tagExists(tag)) {
            hotSearchService.recordClick(currentUserId(), tag);
        }
        return Result.ok();
    }

    /**
     * 当前登录用户 ID；匿名返回 {@code null}。
     *
     * <p>两个用途共用同一个取值：热搜去重键（匿名落 {@code anon} 同一桶）与兴趣画像
     * （匿名无画像，调用方按 null 跳过）。刻意不用 {@code requireUserId()}——
     * 搜索允许匿名，那里会直接抛「请先登录」。</p>
     */
    private String currentUserId() {
        return UserContextHolder.get() == null ? null : UserContextHolder.get().userId();
    }
}
