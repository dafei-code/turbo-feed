package com.turbofeed.gateway.service.search;

import com.turbofeed.shared.redis.RedisKeys;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.service.signal.ModerationSignalService;
import com.turbofeed.gateway.service.signal.ModerationSignalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisZSetCommands.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 话题搜索（S1）：读引擎维护的有序倒排 {@code tf:tag:media:z:{tag}}，过滤 + 回查 + 游标翻页。
 *
 * <h3>为什么索引读在网关、写却在引擎</h3>
 * <p>倒排索引由 {@code TagIndexService}（引擎）在内容入流/下架时维护——那里是唯一知道
 * 「内容何时进入公域」的地方。而<b>回查正文必须走网关</b>：只有网关持有 DataSource，
 * 引擎无 DB 连接。二者共享同一 Redis（{@code tf:mod:} 已是同款跨服务 KV 先例），
 * 因此网关直读索引、按需回表，不引入跨服务 HTTP。</p>
 *
 * <h3>为什么回查不会退化成全分片广播</h3>
 * <p>倒排成员是 {@code timelineKey = post/{userId}/{uuid}}，<b>自带 userId</b>；
 * 而 {@code media} 表分片键正是 {@code user_id} —— 逐条按 (user_id, post_id) 回查
 * <b>单分片精准命中</b>，128 表广播被彻底绕开。这是整个方案成立的前提。</p>
 *
 * <h3>审核过滤：fail-close（与推荐流的 fail-open 相反）</h3>
 * <p>推荐流推错内容是体验问题；<b>搜索是用户主动检索</b>，搜出违规内容属于「平台主动提供」。
 * 因此本类对每条候选都查 {@code tf:mod:} 信号：处置为 {@code INTERCEPT} / {@code MONITOR}
 * 的内容直接剔除，作者被封禁或健康分 &lt; 60 也剔除；<b>信号读异常时按「不安全」处理直接跳过该条</b>
 * （宁可不返回，不可放行）。</p>
 *
 * <h3>已知边界（S1）</h3>
 * <ul>
 *   <li>只覆盖 Redis 索引的 7 天窗口（与时间线分桶 TTL 对齐），老内容需等 S3 冷补；</li>
 *   <li>历史单图数据（{@code timelineKey} 无 userId 段）无法单分片路由，S1 直接跳过并计入日志；</li>
 *   <li>排序位相同（同一毫秒入流）的内容在页边界可能被跳过——真实发布节奏下概率极低，
 *       S3 走 DB 时由 {@code (created_at, post_id)} 复合游标彻底解决。</li>
 * </ul>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** 有序倒排键前缀（与引擎 {@code TagIndexService} 同源，勿单边修改）。 */
    private static final String ORDERED_KEY_PREFIX = RedisKeys.TAG_MEDIA_ORDERED;

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    /** 单次扫描上限：过滤会损耗候选，故多取一些，避免「一页不足」频繁发生。 */
    private static final int SCAN_FACTOR = 3;
    private static final int MAX_SCAN = 150;

    /** 健康分低于此值的内容不进公域（与 P0-b 阶梯 {@code <60 限投稿} 逐字对齐）。 */
    private static final int HEALTH_DROP_BELOW = 60;

    private final StringRedisTemplate redis;
    private final MediaJdbcRepository mediaRepository;
    private final ModerationSignalService signalService;

    public SearchService(StringRedisTemplate redis,
                         MediaJdbcRepository mediaRepository,
                         ModerationSignalService signalService) {
        this.redis = redis;
        this.mediaRepository = mediaRepository;
        this.signalService = signalService;
    }

    /**
     * 按话题搜索内容（时间倒序，游标翻页）。
     *
     * @param rawTag 用户输入的话题（可带 {@code #}，大小写不敏感）
     * @param size   本页条数（默认 20，上限 50）
     * @param cursor 上一页返回的 {@code nextCursor}；首页传空
     * @return 结果页；任何异常都退化为空页，绝不把索引故障抛给用户
     */
    public SearchPage searchByTag(String rawTag, int size, String cursor) {
        String tag = normalizeTag(rawTag);
        if (tag.isEmpty()) {
            return SearchPage.empty();
        }
        int limit = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String key = ORDERED_KEY_PREFIX + tag;

        Double cursorScore = decodeCursor(cursor);
        double maxScore = cursorScore == null ? Double.MAX_VALUE : cursorScore;
        int scan = Math.min(limit * SCAN_FACTOR, MAX_SCAN);

        List<ScoredKey> candidates;
        try {
            Set<ZSetOperations.TypedTuple<String>> raw =
                    redis.opsForZSet().reverseRangeByScoreWithScores(key, 0d, maxScore, 0, scan);
            candidates = toCandidates(raw, cursorScore);
        } catch (Exception e) {
            log.warn("话题索引读取失败（降级为空页）: tag={}, {}", tag, e.getMessage());
            return SearchPage.empty();
        }
        if (candidates.isEmpty()) {
            return new SearchPage(List.of(), "", false, zCard(key));
        }

        // 批量取内容处置信号：一次往返拿全这一批，避免逐条打 Redis
        Map<String, ModerationSignalView.DispositionView> dispositions =
                batchDisposition(candidates);

        List<SearchPage.Item> items = new ArrayList<>(limit);
        Map<Long, Boolean> authorVisible = new HashMap<>();
        int lastAccepted = -1;
        boolean hasMore = false;

        for (int i = 0; i < candidates.size(); i++) {
            ScoredKey c = candidates.get(i);
            if (!visibleByContent(c.key, dispositions)) {
                continue;
            }
            Long uid = userIdOf(c.key);
            if (uid == null) {
                // 历史单图数据（无 userId 段）无法单分片回查，S1 跳过
                log.debug("搜索结果跳过无法路由的历史键: {}", c.key);
                continue;
            }
            if (!authorVisible.computeIfAbsent(uid, this::authorVisible)) {
                continue;
            }
            SearchPage.Item item = toItem(c.key, uid);
            if (item == null) {
                continue;
            }
            if (items.size() == limit) {
                hasMore = true;
                break;
            }
            items.add(item);
            lastAccepted = i;
        }

        // 扫描满窗也意味着「可能还有」，需让前端继续翻
        if (!hasMore && candidates.size() == scan) {
            hasMore = true;
        }
        String next = "";
        if (hasMore && lastAccepted >= 0) {
            next = encodeCursor(candidates.get(lastAccepted).score);
        }
        return new SearchPage(items, next, hasMore && !next.isEmpty(), zCard(key));
    }

    /**
     * 按昵称搜索用户（S2，归一化后<b>精确匹配</b>）。
     *
     * @param rawQuery 昵称（大小写不敏感、忽略首尾空白）
     * @param size     最多返回条数（默认 20，上限 50）
     */
    public SearchPage.UserPage searchUser(String rawQuery, int size) {
        String norm = SearchIndexWriter.normalize(rawQuery);
        if (norm.isEmpty()) {
            return SearchPage.UserPage.empty();
        }
        int limit = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String key = SearchIndexWriter.USER_INDEX_PREFIX + norm;
        try {
            Set<String> members = redis.opsForZSet().reverseRange(key, 0, limit - 1);
            if (members == null || members.isEmpty()) {
                return SearchPage.UserPage.empty();
            }
            List<SearchPage.UserItem> items = new ArrayList<>(members.size());
            for (String m : members) {
                int sep = m.indexOf('\u0000');
                String idPart = sep > 0 ? m.substring(0, sep) : m;
                String nick = sep > 0 ? m.substring(sep + 1) : "";
                try {
                    items.add(new SearchPage.UserItem(Long.parseLong(idPart), nick));
                } catch (NumberFormatException ignore) {
                    // 脏成员跳过，不影响其余
                }
            }
            Long total = redis.opsForZSet().zCard(key);
            return new SearchPage.UserPage(items, total == null ? items.size() : total);
        } catch (Exception e) {
            log.warn("用户索引读取失败（降级为空页）: q={}, {}", norm, e.getMessage());
            return SearchPage.UserPage.empty();
        }
    }

    /**
     * 搜索联想（S3）：按前缀从词典 {@code tf:search:lex} 取话题 + 用户。
     *
     * <p>词典成员带类型前缀（{@code tag:} / {@code user:}），因此要<b>分别</b>在两个前缀区间
     * 做 lex 范围取，再按类型合并。返回的是「已经存在的词」而非内容——
     * 点进去后由 {@link #searchByTag} / {@link #searchUser} 承接。</p>
     *
     * @param rawQuery 前缀（可带 {@code #}）
     * @param size     最多返回条数（默认 10，上限 20）
     */
    public SuggestResult suggest(String rawQuery, int size) {
        String q = normalizeTag(rawQuery);
        if (q.isEmpty()) {
            return SuggestResult.empty();
        }
        int limit = size <= 0 ? 10 : Math.min(size, 20);
        int perType = Math.min(limit, 20);
        try {
            List<SuggestResult.Item> out = new ArrayList<>(limit);
            collectLex(SearchIndexWriter.LEX_TAG, q, perType, "tag", out);
            collectLex(SearchIndexWriter.LEX_USER, q, perType, "user", out);
            if (out.size() > limit) {
                return new SuggestResult(new ArrayList<>(out.subList(0, limit)));
            }
            return new SuggestResult(out);
        } catch (Exception e) {
            log.warn("联想词典读取失败（降级为空）: q={}, {}", q, e.getMessage());
            return SuggestResult.empty();
        }
    }

    /**
     * 该话题在有序倒排里是否<b>真的有内容</b>（只问「有没有」，不取内容）。
     *
     * <p><b>用途：热搜点击上报的准入校验。</b>点击是客户端上报的，若不做校验，
     * 任何人都能凭空造一个词反复上报，把它顶上榜首——榜单上会出现点进去什么都没有的幽灵词。
     * 有了这条校验，能进榜的词<b>必然是索引里真实存在的话题</b>。</p>
     *
     * <p><b>fail-close</b>：读不到索引时按「不存在」处理。统计少记一次无损失，
     * 放一个可疑词进榜才是麻烦。</p>
     *
     * @param rawTag 话题词（可带 {@code #}，大小写不敏感）
     * @return 索引里有至少一条内容才返回 {@code true}
     */
    public boolean tagExists(String rawTag) {
        String tag = normalizeTag(rawTag);
        if (tag.isEmpty()) {
            return false;
        }
        try {
            Long n = redis.opsForZSet().zCard(ORDERED_KEY_PREFIX + tag);
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("话题存在性校验失败（按无内容处理）: tag={}, {}", tag, e.getMessage());
            return false;
        }
    }

    /** 在 {@code {type}:{prefix}} 区间做字典序范围取（上限 {@code \uffff} 覆盖全部常见字符）。 */
    private void collectLex(String typePrefix, String prefix, int limit, String type,
                            List<SuggestResult.Item> out) {
        Range range = new Range()
                .gte(typePrefix + prefix)
                .lte(typePrefix + prefix + "\uffff");
        Set<String> members = redis.opsForZSet()
                .rangeByLex(SearchIndexWriter.LEX_KEY, range, Limit.limit().count(limit));
        if (members == null) {
            return;
        }
        for (String m : members) {
            if (m.length() <= typePrefix.length()) {
                continue;
            }
            out.add(new SuggestResult.Item(type, m.substring(typePrefix.length())));
        }
    }

    // ------------------------------------------------------------------
    // 过滤
    // ------------------------------------------------------------------

    /** 内容维处置：INTERCEPT（下架）/ MONITOR（限公域）一律不进搜索结果。 */
    private boolean visibleByContent(String key,
                                     Map<String, ModerationSignalView.DispositionView> dispositions) {
        ModerationSignalView.DispositionView d = dispositions.get(key);
        if (d == null || d.action() == null) {
            return true;
        }
        return !("INTERCEPT".equals(d.action()) || "MONITOR".equals(d.action()));
    }

    /** 作者维：封禁或健康分低于阈值的内容不出现在公域（与推荐召回过滤对齐）。 */
    private boolean authorVisible(long userId) {
        try {
            ModerationSignalView.HealthView h = signalService.getAccountHealth(userId);
            if (h == null) {
                return true;
            }
            if ("BANNED".equals(h.tier())) {
                return false;
            }
            return h.score() >= HEALTH_DROP_BELOW;
        } catch (Exception e) {
            // fail-close：搜索场景宁可少出结果，不可放行（与推荐流的 fail-open 相反）
            log.warn("读作者健康分失败，按不可见处理: userId={}, {}", userId, e.getMessage());
            return false;
        }
    }

    private Map<String, ModerationSignalView.DispositionView> batchDisposition(List<ScoredKey> candidates) {
        List<String> keys = new ArrayList<>(candidates.size());
        for (ScoredKey c : candidates) {
            keys.add(c.key);
        }
        try {
            Map<String, ModerationSignalView.DispositionView> m = signalService.batchGetMediaDisposition(keys);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            log.warn("批量读内容处置信号失败（本页候选全部按不可见处理）: {}", e.getMessage());
            // fail-close：拿不到安全信号就不返回结果
            Map<String, ModerationSignalView.DispositionView> blocked = new HashMap<>();
            for (String k : keys) {
                blocked.put(k, new ModerationSignalView.DispositionView("INTERCEPT", "UNKNOWN", "FAIL_CLOSED", 0L));
            }
            return blocked;
        }
    }

    // ------------------------------------------------------------------
    // 回查
    // ------------------------------------------------------------------

    /**
     * 按 timelineKey 回查内容并组装搜索结果。
     *
     * <p>整帖语义：帖内<b>任一</b>图片未处于 APPROVED，整帖不进搜索结果——
     * 否则会出现「封面能看、点进去发现部分图已下架」的割裂体验，也避免漏放已驳回图片。</p>
     */
    private SearchPage.Item toItem(String timelineKey, long userId) {
        try {
            List<MediaItem> rows;
            if (timelineKey.startsWith("post/")) {
                rows = mediaRepository.listPostImages(userId, timelineKey);
            } else {
                MediaItem one = mediaRepository.findMedia(timelineKey, userId);
                rows = one == null ? List.of() : List.of(one);
            }
            if (rows.isEmpty()) {
                return null;
            }
            for (MediaItem r : rows) {
                if (r.status() != MediaStatus.APPROVED) {
                    return null;
                }
            }
            MediaItem cover = rows.get(0);
            List<String> images = new ArrayList<>(rows.size());
            for (MediaItem r : rows) {
                images.add(r.url());
            }
            List<String> tags = cover.tags() == null ? List.of() : cover.tags();
            return new SearchPage.Item(timelineKey, cover.mediaId(), cover.url(), images,
                    cover.caption(), tags, String.valueOf(userId));
        } catch (Exception e) {
            log.warn("搜索结果回查失败（跳过该条）: key={}, {}", timelineKey, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 游标 / 工具
    // ------------------------------------------------------------------

    private record ScoredKey(String key, double score) {
    }

    private static List<ScoredKey> toCandidates(Set<ZSetOperations.TypedTuple<String>> raw, Double cursorScore) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ScoredKey> out = new ArrayList<>(raw.size());
        for (ZSetOperations.TypedTuple<String> t : raw) {
            String v = t.getValue();
            Double s = t.getScore();
            if (v == null || s == null) {
                continue;
            }
            // 游标为「上一页最后一条的排序位」，严格小于它才属于下一页
            if (cursorScore != null && s >= cursorScore) {
                continue;
            }
            out.add(new ScoredKey(v, s));
        }
        return out;
    }

    private static String encodeCursor(double score) {
        String raw = String.valueOf(score);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Double decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Double.parseDouble(raw);
        } catch (RuntimeException e) {
            // 游标损坏按首页处理，不报错给用户
            return null;
        }
    }

    /**
     * 归一化话题：去 {@code #}、去空白、转小写（与 {@code CaptionTagParser} 的写入口径一致）。
     *
     * <p>{@code public} 是为了让 {@code HotSearchService} 复用<b>同一份</b>归一化实现：
     * 热搜榜的 key 与倒排索引的 key 必须逐字一致，若各写一份，改口径时必然有一边漏改，
     * 表现为「榜上有词、点进去搜不到」。</p>
     */
    public static String normalizeTag(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        return t.trim().toLowerCase(Locale.ROOT);
    }

    /** 从 {@code post/{userId}/{uuid}} 或 {@code media/{userId}/xx.jpg} 中取出 userId。 */
    static Long userIdOf(String timelineKey) {
        if (timelineKey == null) {
            return null;
        }
        String[] parts = timelineKey.split("/");
        if (parts.length < 2) {
            return null;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long zCard(String key) {
        try {
            Long n = redis.opsForZSet().zCard(key);
            return n == null ? 0L : n;
        } catch (Exception e) {
            return 0L;
        }
    }
}
