package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.feedengine.contract.ItemViewCodec;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.RecommendedItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 公域推荐流读取服务：时间线读取 + 短 TTL 旁路缓存。
 *
 * <p><b>为什么缓存放在引擎而不是网关</b>：缓存是"离数据最近"才有效的组件。时间线 ZSET 在引擎
 * 进程的 Redis 里读写，若网关再压一层缓存，就会出现"两层缓存各自过期、失效路径要跨进程通知"
 * 的复杂度；拆分的意义之一就是让读模型与其缓存同域。网关因此退化为纯转发 + 降级决策。</p>
 *
 * <p><b>TTL + 主动失效（两段式）</b>：TTL 只是<b>兜底</b>（匿名 15s / 登录 5s）；
 * 时间线发生 append / remove 时由 {@link #invalidate()} 主动失效，让新内容<b>立刻</b>可见。
 * 只依赖 TTL 的代价实测很明显：补投一条内容后必须等满 TTL 才能在发现流出现，
 * 对用户就是"我发了却看不到"。</p>
 *
 * <p><b>失效方式 = 全局版本号（A6）</b>：{@link #invalidate()} 只对 {@link #REC_VERSION_KEY}
 * 做一次 {@code INCR}，所有旧版本缓存 Key 立即不可达（不再被拼出）、随各自 TTL 自然回收。
 * 这取代了改造前「枚举前 5 页 × 2 种页大小共 10 个确定 Key 逐个 {@code DEL}」的做法——
 * 枚举删除在只缓存匿名页时尚可（Key 数量有界），但<b>无法扩展到登录用户维度</b>（用户数无界）。
 * 版本号方案同时天然规避两个老问题：不需要 {@code SCAN}（单线程 Redis 上扫大 keyspace 会阻塞
 * 其它命令），也不触碰 Cluster 下多 Key 命令的 {@code CROSSSLOT} 限制（{@code INCR} 是单 Key 命令）。</p>
 *
 * <p>⚠️ <b>缓存作用域：登录用户结果也缓存，但 Key 必须含用户身份</b>。见
 * {@link #recommended(int, int, String)} 与 {@link #REC_VERSION_KEY}。
 * 匿名与登录 Key 拼装格式不同（后者含 {@code u:{userId}}），<b>绝不能让个性化结果落到不含身份的
 * Key 上</b>，否则用户 B 会在 TTL 内读到用户 A 的个性化排序（含 A 的兴趣加权与"不感兴趣"打压），
 * 即<b>跨用户串号</b>。这不是理论风险——引入个性化时（{@code ee1e2ae}）只改了
 * {@code readPage(userId, ...)} 的入参、漏改缓存 Key，实测会复现。改动缓存逻辑时不得绕过。</p>
 *
 * <p><b>为什么给个性化结果也加缓存（A6 的取舍）</b>：本类原注释曾论证「不给个性化结果加缓存」，
 * 理由是命中率近零、失效会失控（Key 带 userId → 用户数无界 → 删不干净）。<b>后一条已被全局版本号
 * 解决</b>；前一条的前提也变了——真实流量中绝大多数请求来自登录用户，只缓存匿名页等于
 * 缓存对主流量无效，每次翻页都要完整执行「多池读取 + 多路召回 + 粗排 + 精排 + 多样性重排」。
 * 现在登录用户页以 5s 短 TTL 缓存，吸收客户端高频重复请求（下拉刷新 / 页面回退 / 同页重复拉取），
 * 而 5s 内用户的新互动由 {@link #invalidate()} 的版本号自增即时生效，不牺牲"发布后立刻可见"。</p>
 *
 * <p><b>fail-open</b>：缓存读写任一环节异常都只告警，直接回落到时间线实时读取；版本号读不到时
 * <b>跳过缓存</b>（不拼 Key、不读写），保守但易推理。时间线本身也 fail-open
 * （见 {@link FeedTimelineStore}），因此本服务永不因 Redis 异常向调用方抛错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendedFeedService {

    private final FeedTimelineStore feedTimelineStore;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 推荐流缓存 TTL：15s（兜底；正常路径由 {@link #invalidate()} 主动失效）。 */
    private static final Duration REC_CACHE_TTL = Duration.ofSeconds(15);
    /**
     * 登录用户个性化缓存 TTL（A6）：5s，<b>显著短于</b>匿名的 15s。
     *
     * <p>取更短是因为个性化结果对「用户刚发生的互动」更敏感（点赞/关注后期望立刻反映在排序上），
     * 而匿名结果只随时间线变化。5s 足以吸收客户端的高频重复请求（下拉刷新、页面回退、
     * 同一页重复拉取），又不会让用户感到「我刚点了赞但排序没动」。</p>
     */
    private static final Duration USER_REC_CACHE_TTL = Duration.ofSeconds(5);
    private static final String REC_KEY_PREFIX = "tf:feed:rec:";
    /**
     * 推荐流缓存<b>全局版本号</b> Key（A6）。
     *
     * <p>{@link #invalidate()} 只对它做一次 {@code INCR}，所有旧版本缓存 Key 立即<b>不可达</b>
     * （不再被拼出）、随 TTL 自然回收。这是本类原注释（「若将来量级确实需要个性化缓存，正确做法是
     * 给 key 加全局版本号」）指定的方案，也是让登录用户结果可缓存的前提。</p>
     */
    private static final String REC_VERSION_KEY = "tf:feed:rec:version";

    /**
     * 读取公域推荐流（入流时间倒序，分页）。
     *
     * @param page   页码（从 0 开始）
     * @param size   单页条数（≤0 兜底 20）
     * @param userId 个性化用户（已登录；匿名为 {@code null}）；{@code null} 退化为纯「入流时刻 + 完播率」排序。
     *               非 null 时结果按<b>用户身份维度</b>缓存（Key 含 userId，TTL 更短），
     *               绝不与匿名用户或其他用户共用 Key
     * @return 当前页内容；空表示无更多内容或 Redis 不可用（调用方据此决定降级口径）
     *
     * <p><b>⚠️ 缓存 Key 必须含用户身份，否则跨用户串号</b>（A6）。
     * 匿名与登录用户都缓存，但 Key 格式不同：
     * <ul>
     *   <li>匿名：{@code tf:feed:rec:{version}:{page}:{limit}}——跨用户共享，结果本就与身份无关；</li>
     *   <li>登录：{@code tf:feed:rec:{version}:u:{userId}:{page}:{limit}}——<b>含 userId</b>。</li>
     * </ul>
     * 绝不能让个性化结果落到不含身份的 Key 上，否则用户 B 会在 TTL 内读到用户 A 的个性化排序
     * （含 A 的兴趣加权与"不感兴趣"打压）。这不是理论风险——引入个性化时（{@code ee1e2ae}）
     * 只改了 {@code readPage(userId, ...)} 的入参、漏改缓存 Key，实测会复现。</p>
     *
     * <p><b>为什么登录用户也缓存（A6 的取舍，推翻本方法此前的结论）</b>：此前只在匿名页缓存，
     * 理由是「命中率近零」与「Key 带 userId 则用户数无界、失效删不干净」。
     * <b>后者已被全局版本号解决</b>——{@link #invalidate()} 只 {@code INCR} 一个计数，
     * 旧 Key 自然不可达、随 TTL 回收，与用户数无关；<b>前者的前提也变了</b>——真实流量绝大多数
     * 来自登录用户，只缓存匿名页等于缓存对主流量无效，每次翻页都要完整跑一遍
     * 「多池读取 + 多路召回 + 粗排 + 精排 + 多样性重排」。现在登录页以 {@link #USER_REC_CACHE_TTL}
     * 5s 短 TTL 缓存（显著短于匿名的 15s），吸收客户端高频重复请求；取更短是因为个性化结果对
     * 「用户刚发生的互动」更敏感，5s 足以吸收重复拉取又不会让用户感到"我点了赞排序没动"。</p>
     *
     * <p><b>版本号不可得时跳过缓存</b>：{@link #readVersion()} 返回 {@code null}（Redis 不可达）
     * 则不拼 Key、不读不写，直接实时读时间线。刻意不用「当版本 0」兜底——那会在抖动窗口内
     * 把不同用户的结果写到同一版本的 Key 上，一旦 Key 拼装漏了身份维度即串号。</p>
     */
    public List<RecommendedItem> recommended(int page, int size, String userId) {
        int limit = size <= 0 ? 20 : size;
        // A6：匿名与登录用户都缓存，但 Key 作用域不同——
        //   匿名：tf:feed:rec:{version}:{page}:{limit}          （跨用户共享）
        //   登录：tf:feed:rec:{version}:u:{userId}:{page}:{limit}（含用户身份，绝不跨用户复用）
        // 两者都嵌全局版本号，因此 invalidate() 一次 INCR 即可让全部旧缓存失效。
        boolean personalized = userId != null && !userId.isBlank();
        String version = readVersion();
        String key = null;
        Duration ttl = null;
        if (version != null) {
            key = personalized
                    ? REC_KEY_PREFIX + version + ":u:" + userId + ":" + page + ":" + limit
                    : REC_KEY_PREFIX + version + ":" + page + ":" + limit;
            ttl = personalized ? USER_REC_CACHE_TTL : REC_CACHE_TTL;
        }
        // version == null（Redis 不可达）→ key 为 null → 跳过缓存直接实时读，行为等同改造前的降级路径

        if (key != null) {
            try {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    // 走统一入口：本处原是裸 readValue 的旁路，缓存里若混入了高版本条目会按错误语义渲染
                    return ItemViewCodec.parseListRecommended(objectMapper, cached);
                }
            } catch (Exception e) {
                log.warn("推荐流缓存读取失败，回落时间线实时读取: key={}, {}", key, e.getMessage());
            }
        }

        List<RecommendedItem> fresh = feedTimelineStore.readPage(userId, page, limit);

        if (key != null) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fresh), ttl);
            } catch (Exception e) {
                log.warn("推荐流缓存写入失败（不影响本次读取）: key={}, {}", key, e.getMessage());
            }
        }
        return fresh;
    }

    /**
     * 游标分页读取公域推荐流（keyset）：返回 {@link FeedRecommendedPage}（含 {@code nextCursor}）。
     *
     * <p>与 {@link #recommended(int, int, String)}（页码分页）共用缓存与读路径——
     * 游标模式以游标编码进缓存 Key（与页码模式互不串），并据最后一页末条的<b>入流 score</b>
     * （经 {@code tf:feed:idx} 反查 + ZSCORE，见 {@link FeedTimelineStore#inflowScoreOf}）计算
     * {@code nextCursor} 下发给前端。{@code cursor == null} 时退化为页码分页（不产出游标）。</p>
     *
     * @param page   页码（cursor 为 null 时生效，从 0 起）
     * @param size   单页条数（≤0 兜底 20）
     * @param userId 个性化用户（已登录；匿名为 {@code null}）
     * @param cursor 续拉游标（{@code null} = 首页，走页码分页）
     */
    public FeedRecommendedPage recommendedPage(int page, int size, String userId, FeedCursor cursor) {
        int limit = size <= 0 ? 20 : size;
        if (cursor == null) {
            // 页码模式：复用既有分页 + 缓存（键含页码），不产出游标
            List<RecommendedItem> items = recommended(page, limit, userId);
            return new FeedRecommendedPage(items, "", items.size() == limit, page);
        }
        // 游标模式：本页绝对翻页序号由游标 depth 携带（首页 depth=0，逐页 +1），供曝光特征定位
        int depth = cursor.depth();
        // 游标模式：以游标 score 为排他上界读取 + 游标维度缓存
        boolean personalized = userId != null && !userId.isBlank();
        String version = readVersion();
        String key = null;
        Duration ttl = null;
        if (version != null) {
            key = personalized
                    ? REC_KEY_PREFIX + version + ":u:" + userId + ":cursor:" + cursor.encode()
                    : REC_KEY_PREFIX + version + ":cursor:" + cursor.encode();
            ttl = personalized ? USER_REC_CACHE_TTL : REC_CACHE_TTL;
        }
        List<RecommendedItem> fresh;
        if (key != null) {
            try {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return wrapWithCursor(ItemViewCodec.parseListRecommended(objectMapper, cached), limit, depth);
                }
            } catch (Exception e) {
                log.warn("推荐流游标缓存读取失败，回落实时读: key={}, {}", key, e.getMessage());
            }
        }
        fresh = feedTimelineStore.readPage(userId, limit, cursor);
        if (key != null) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fresh), ttl);
            } catch (Exception e) {
                log.warn("推荐流游标缓存写入失败（不影响本次读取）: key={}, {}", key, e.getMessage());
            }
        }
        return wrapWithCursor(fresh, limit, depth);
    }

    /** 包一页内容为 {@link FeedRecommendedPage}，并据末条的入流 score 计算 {@code nextCursor}（depth+1）。 */
    private FeedRecommendedPage wrapWithCursor(List<RecommendedItem> items, int limit, int page) {
        if (items == null) {
            items = List.of();
        }
        String next = "";
        if (!items.isEmpty()) {
        RecommendedItem last = items.get(items.size() - 1);
        Long s = feedTimelineStore.inflowScoreOf(last.item().timelineKey());
            if (s != null) {
                // nextCursor 携带 depth+1：下一页续拉时其绝对翻页序号即本页 +1
                next = FeedCursor.of(s, last.item().timelineKey(), page + 1).encode();
            }
        }
        boolean hasMore = items.size() == limit;
        return new FeedRecommendedPage(items, next, hasMore, page);
    }

    /**
     * 读全局缓存版本号；不可得时返回 {@code null}（调用方据此<b>跳过缓存</b>、直接实时读）。
     *
     * <p>刻意用「读不到就不缓存」而不是「读不到就当版本 0」：后者会在 Redis 抖动窗口内
     * 把所有用户的结果写到同一个版本 0 的 Key 上，恢复后 {@code INCR} 到 1 虽能失效它们，
     * 但窗口内不同用户可能读到彼此的 Key（若 Key 拼装漏了身份维度即串号）。返回 {@code null}
     * 让降级路径根本不碰缓存，是最保守也最易推理的选择。</p>
     */
    private String readVersion() {
        try {
            String v = redisTemplate.opsForValue().get(REC_VERSION_KEY);
            return v != null ? v : "0";
        } catch (Exception e) {
            log.warn("推荐流缓存版本号读取失败（本次跳过缓存，直接实时读）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 时间线变更后主动失效<b>全部</b>推荐流缓存（发布 / 下架 / 补投后调用）——含匿名页与所有登录用户页。
     *
     * <p><b>A6：一次 {@code INCR} 取代枚举 {@code DEL}</b>。改造前只失效匿名页（登录用户不缓存，
     * 故"足够"），且靠「前 5 页 × 2 种页大小 = 10 个确定 Key」逐个 {@code DEL}。
     * 引入登录用户缓存后，用户维度 Key 数量无界，枚举删除这条路<b>根本走不通</b>；
     * 全局版本号把失效成本压回 O(1)：{@code INCR} 之后，所有旧版本 Key 立即不可达
     * （不再被拼出），随各自 TTL（匿名 15s / 登录 5s）自然回收。</p>
     *
     * <p><b>为什么不用 SCAN</b>：按 {@code tf:feed:rec:*} 通配扫描再删，在 keyspace 变大后
     * 会长时间占用 Redis 单线程，把一次"内容发布"放大成全局抖动。版本号方案连"删"都不需要，
     * 天然规避该问题，也规避了 Cluster 下多 Key {@code DEL} 的 {@code CROSSSLOT} 限制
     * （{@code INCR} 是单 Key 命令）。</p>
     *
     * <p><b>版本号 Key 不设 TTL</b>：它只存一个整数、成本可忽略，且<b>绝不能过期</b>——
     * 一旦过期回退到初始值，就可能与残留的同版本缓存 Key 碰撞而读到脏数据。
     * 该 Key 在每次 {@link #recommended} 都被读取，是全局最热的 Key 之一，
     * 在 {@code maxmemory} 淘汰下必然晚于缓存 Key 被淘汰，因此不设 TTL 是安全的。</p>
     *
     * <p><b>fail-open</b>：{@code INCR} 失败只告警——最坏情况退化为"等 TTL 过期"（≤15s），
     * 与改造前一致，不会让时间线写入（主流程）失败。</p>
     */
    public void invalidate() {
        try {
            Long v = redisTemplate.opsForValue().increment(REC_VERSION_KEY);
            log.debug("推荐流缓存已失效（全局版本号 → {}）", v);
        } catch (Exception e) {
            // 失败退化为 TTL 兜底（匿名 15s / 登录 5s），不阻断时间线写入
            log.warn("推荐流缓存失效失败（退化为 TTL 到期）: {}", e.getMessage());
        }
    }
}
