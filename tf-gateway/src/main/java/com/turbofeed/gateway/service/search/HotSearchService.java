package com.turbofeed.gateway.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热搜榜（S4）：统计「真实发生过的有效搜索」与「搜完之后有没有点进去」，并按窗口聚合出榜单。
 *
 * <h3>为什么热搜不能直接复用联想词典 {@code tf:search:lex}</h3>
 * <p>词典的成员分数被<b>强制写成 0</b>——那是 {@code ZRANGEBYLEX} 做前缀扫描的前提
 * （一旦分数不齐，Redis 会退化成按分数排序，前缀区间扫描静默失效）。所以「热度」不能
 * 塞进词典，必须另立一个按分数排序的 ZSET。联想负责「补全」，热搜负责「排序」，两件事分开。</p>
 *
 * <h3>为什么分成「搜索次数」和「点击次数」两个桶</h3>
 * <p>只看搜索量，榜单会被<b>高频但低价值</b>的词占住：用户搜了很多次却每次都空手而归
 * （或者点进去立刻退出），说明这个词没内容、也没价值，它凭「搜得多」霸榜就是在挤走好词。
 * 抖音式的热搜看的是<b>综合热度</b>，不是一个计数。<br>
 * 因此两个维度分开存、分开暴露（{@code searches} / {@code clicks}），排序用可配置的加权和。</p>
 *
 * <p><b>为什么不把点击直接加权写进同一个 ZSET</b>：那样 score 就是「0.2 + 3.0 + 0.2…」的混合物，
 * 事后既看不出搜了多少次、也调不动权重，只能重跑数据。两个原始计数保留着，权重随时能改、榜单随时能重算。</p>
 *
 * <h3>为什么按天分桶，而不是一个大 ZSET</h3>
 * <ul>
 *   <li><b>榜单会腐烂</b>：一个永不清零的累加键，运行越久越被早期热词霸占，最终谁都上不去；</li>
 *   <li><b>天然可回溯</b>：按天分桶后，「昨天/上周的热搜」不需要额外存储，改窗口即可；</li>
 *   <li><b>过期成本低</b>：桶键设 8 天 TTL，自然淘汰，无需定时清理作业。</li>
 * </ul>
 * 读的时候合并最近 {@code WINDOW_DAYS} 天：每天各取 Top-N 再全局排序。
 * 这个近似是<b>精确的</b>——任何能进全局 Top-N 的词，必然也在它得分最高的那天的 Top-N 里。
 *
 * <h3>为什么要做同人同词去重</h3>
 * <p>没有去重的话，一个人反复刷新同一个词就能把它顶到榜首（榜单是被刷得最狠的接口之一）。
 * 这里用 {@code SET NX + 60s TTL} 做最小成本抑制：同一用户在同一分钟内对同一个词只计一次。
 * 匿名请求按同一桶计——宁可少计，不可被刷。</p>
 *
 * <h3>什么时候计入</h3>
 * <ul>
 *   <li><b>搜索</b>：首页搜索、且索引里该话题 {@code total > 0} 时计一次。翻页不是新搜索
 *       （否则深度翻页能把同一个词刷上去）；搜不到结果的词不进榜，避免把用户往空页带。</li>
 *   <li><b>点击</b>：客户端上报「从搜索结果点进去了」时计一次，且<b>该词必须真的有内容</b>
 *       （由 {@code SearchService#tagExists} 校验）。没有这条校验，任何人都能凭空造词刷榜。</li>
 * </ul>
 *
 * <h3>fail-open</h3>
 * <p>整个类是<b>旁路统计</b>：读写异常都只记日志，绝不影响搜索本身返回结果。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class HotSearchService {

    private static final Logger log = LoggerFactory.getLogger(HotSearchService.class);

    /** 搜索日桶前缀：{@code tf:search:hot:{yyyyMMdd}}，member = 归一化后的词，score = 当日搜索次数。 */
    static final String HOT_PREFIX = "tf:search:hot:";
    /** 点击日桶前缀：{@code tf:search:click:{yyyyMMdd}}，member 同上，score = 当日点击次数。 */
    static final String CLICK_PREFIX = "tf:search:click:";
    /** 去重键前缀：{@code tf:search:hot:dedup:{yyyyMMdd}:{userKey}:{word}}，存活 60s。 */
    static final String DEDUP_PREFIX = "tf:search:hot:dedup:";
    /** 点击去重键前缀：{@code tf:search:click:dedup:{yyyyMMdd}:{userKey}:{word}}，存活 60s。 */
    static final String CLICK_DEDUP_PREFIX = "tf:search:click:dedup:";

    /** 榜单窗口：合并最近 7 天（覆盖「今日 / 本周」两种口径）。 */
    static final int WINDOW_DAYS = 7;
    /** 日桶存活 8 天：比窗口多一天，避免跨零点时今天的桶刚写就被淘汰。 */
    private static final Duration DAY_TTL = Duration.ofDays(8);
    /** 同人同词去重窗口。 */
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);
    /** 词长上限：超长串只可能是噪声或攻击载荷，不进榜（也避免把 ZSET 成员撑大）。 */
    private static final int MAX_WORD_LEN = 64;
    /** 榜单条数上限。 */
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;

    /**
     * 按天分桶用的时区：固定东八区。
     *
     * <p>不用 {@code ZoneId.systemDefault()}——容器/CI 的默认时区常常是 UTC，
     * 那会让「今天」在早上 8 点才切换，榜单口径随部署环境漂移。</p>
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;

    /**
     * 一次「点进结果」折算成多少次搜索。
     *
     * <p>默认 3.0 取自「点击是比搜索强得多的意图信号」：搜索只是输入框里敲了字，
     * 点击意味着用户真的消费了内容。这个值<b>只影响排序，不影响两个原始计数</b>，
     * 因此随时可以调，不需要重跑数据。<br>
     * 置 {@code 0} = 榜单只看搜索量（运维想临时关掉点击加权时有用）。</p>
     */
    private final double clickWeight;

    public HotSearchService(StringRedisTemplate redis,
                            @Value("${turbofeed.search.hot.click-weight:3.0}") double clickWeight) {
        this.redis = redis;
        // 只在明显非法（负数 / NaN）时回退默认。⚠️ 不能用 "> 0 ? : 3.0"：
        // 那会把运维显式配置的 0（= 纯搜索量榜单）静默改成 3，配置说了不算是最难查的坑。
        this.clickWeight = (Double.isNaN(clickWeight) || clickWeight < 0) ? 3.0 : clickWeight;
    }

    /**
     * 记录一次有效搜索（搜索结果非空时调用）。
     *
     * @param userKey 当前用户标识；匿名为 {@code null}（按同一桶去重）
     * @param rawQuery 用户原始输入（内部按搜索口径归一化，与倒排键逐字一致）
     */
    public void record(String userKey, String rawQuery) {
        count(userKey, rawQuery, HOT_PREFIX, DEDUP_PREFIX);
    }

    /**
     * 记录一次「从搜索结果点进内容」。
     *
     * <p><b>调用方必须先确认该词真的有内容</b>（{@code SearchService#tagExists}）：
     * 否则任何人都能凭空造词刷榜。去重口径与搜索侧一致（同人同词 60s）。</p>
     *
     * @param userKey 当前用户标识；匿名为 {@code null}
     * @param rawQuery 被点击的搜索词
     */
    public void recordClick(String userKey, String rawQuery) {
        count(userKey, rawQuery, CLICK_PREFIX, CLICK_DEDUP_PREFIX);
    }

    /**
     * 读取当前热搜榜（最近 {@value #WINDOW_DAYS} 天合并）。
     *
     * @param limit 条数（默认 10，上限 50）
     * @return 榜单；任何异常返回空榜单，不向前端暴露故障
     */
    public HotSearchResult top(int limit) {
        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Map<String, Double> searches = mergeDays(HOT_PREFIX, n);
        Map<String, Double> clicks = mergeDays(CLICK_PREFIX, n);
        if (searches.isEmpty() && clicks.isEmpty()) {
            return HotSearchResult.empty();
        }
        Map<String, double[]> byWord = new HashMap<>();
        searches.forEach((w, c) -> byWord.computeIfAbsent(w, k -> new double[2])[0] = c);
        clicks.forEach((w, c) -> byWord.computeIfAbsent(w, k -> new double[2])[1] = c);

        List<HotSearchResult.Item> items = new ArrayList<>(byWord.size());
        for (Map.Entry<String, double[]> e : byWord.entrySet()) {
            double sc = e.getValue()[0] + e.getValue()[1] * clickWeight;
            items.add(new HotSearchResult.Item(e.getKey(), sc, e.getValue()[0], e.getValue()[1]));
        }
        // 分数降序；同分按词字典序，保证同一份数据多次读取顺序稳定（前端不会莫名跳动）
        items.sort(Comparator.comparingDouble(HotSearchResult.Item::score).reversed()
                .thenComparing(HotSearchResult.Item::word));
        if (items.size() > n) {
            items = new ArrayList<>(items.subList(0, n));
        }
        return new HotSearchResult(items);
    }

    /** 合并最近 {@value #WINDOW_DAYS} 天：每天各取 Top-N 再累加（近似精确，见类注释）。 */
    private Map<String, Double> mergeDays(String prefix, int n) {
        Map<String, Double> merged = new HashMap<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = 0; i < WINDOW_DAYS; i++) {
            String key = prefix + today.minusDays(i).format(DAY);
            try {
                Set<ZSetOperations.TypedTuple<String>> tuples =
                        redis.opsForZSet().reverseRangeWithScores(key, 0, n - 1);
                if (tuples == null) {
                    continue;
                }
                for (ZSetOperations.TypedTuple<String> t : tuples) {
                    if (t.getValue() == null || t.getScore() == null) {
                        continue;
                    }
                    merged.merge(t.getValue(), t.getScore(), Double::sum);
                }
            } catch (Exception e) {
                // 某一天的桶读失败不影响其余天（多一天少一天不改榜单量级）
                log.warn("热搜日桶读取失败（跳过该天）: key={}, {}", key, e.getMessage());
            }
        }
        return merged;
    }

    /** 去重 + 计数：先 {@code SETNX} 抢去重键，抢到才 {@code ZINCRBY}，并顺手保住日桶 TTL。 */
    private void count(String userKey, String rawQuery, String bucketPrefix, String dedupPrefix) {
        String word = SearchService.normalizeTag(rawQuery);
        if (word.isEmpty() || word.length() > MAX_WORD_LEN) {
            return;
        }
        String day = LocalDate.now(ZONE).format(DAY);
        try {
            String dedupKey = dedupPrefix + day + ":"
                    + (userKey == null || userKey.isBlank() ? "anon" : userKey) + ":" + word;
            Boolean first = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
            String bucketKey = bucketPrefix + day;
            redis.opsForZSet().incrementScore(bucketKey, word, 1d);
            // 每次都设 TTL：桶键只在首次写入时创建，这里顺带保证它一定会过期
            redis.expire(bucketKey, DAY_TTL);
        } catch (Exception e) {
            // 旁路统计：统计失败不能影响搜索返回
            log.warn("热搜计数失败（忽略）: word={}, {}", word, e.getMessage());
        }
    }
}
