package com.turbofeed.gateway.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 热搜榜（S4）：统计「真实发生过的有效搜索」，并按窗口聚合出榜单。
 *
 * <h3>为什么热搜不能直接复用联想词典 {@code tf:search:lex}</h3>
 * <p>词典的成员分数被<b>强制写成 0</b>——那是 {@code ZRANGEBYLEX} 做前缀扫描的前提
 * （一旦分数不齐，Redis 会退化成按分数排序，前缀区间扫描静默失效）。所以「热度」不能
 * 塞进词典，必须另立一个按分数排序的 ZSET。联想负责「补全」，热搜负责「排序」，两件事分开。</p>
 *
 * <h3>为什么按天分桶，而不是一个大 ZSET</h3>
 * <ul>
 *   <li><b>榜单会腐烂</b>：一个永不清零的累加键，运行越久越被早期热词霸占，最终谁都上不去；</li>
 *   <li><b>天然可回溯</b>：按天分桶后，「昨天/上周的热搜」不需要额外存储，改窗口即可；</li>
 *   <li><b>过期成本低</b>：桶键设 8 天 TTL，自然淘汰，无需定时清理作业。</li>
 * </ul>
 * 读的时候合并最近 {@value #WINDOW_DAYS} 天：每天各取 Top-N 再全局排序。
 * 这个近似是<b>精确的</b>——任何能进全局 Top-N 的词，必然也在它得分最高的那天的 Top-N 里。
 *
 * <h3>为什么要做同人同词去重</h3>
 * <p>没有去重的话，一个人反复刷新同一个词就能把它顶到榜首（抖音这类榜单是被刷得最狠的
 * 接口之一）。这里用 {@code SET NX + 60s TTL} 做最小成本抑制：同一用户在同一分钟内对
 * 同一个词只计一次。匿名请求按同一桶计——宁可少计，不可被刷。</p>
 *
 * <h3>什么时候计入</h3>
 * <p>只在<b>搜索结果非空</b>时计入（由 {@code SearchController} 判定）。原因是：搜不到结果的
 * 词多半是错别字或噪声，进榜后点进去还是空页，属于「把用户往坑里带」。
 * 真想看「热搜无结果词」（选品/内容缺口信号），那是另一个报表，不该混进用户可见的榜单。</p>
 *
 * <h3>fail-open</h3>
 * <p>整个类是<b>旁路统计</b>：读写异常都只记日志，绝不影响搜索本身返回结果。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class HotSearchService {

    private static final Logger log = LoggerFactory.getLogger(HotSearchService.class);

    /** 日桶前缀：{@code tf:search:hot:{yyyyMMdd}}，member = 归一化后的词，score = 当日搜索次数。 */
    static final String HOT_PREFIX = "tf:search:hot:";
    /** 去重键前缀：{@code tf:search:hot:dedup:{yyyyMMdd}:{userKey}:{word}}，存活 60s。 */
    static final String DEDUP_PREFIX = "tf:search:hot:dedup:";

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

    public HotSearchService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记录一次有效搜索（搜索结果非空时调用）。
     *
     * @param userKey 当前用户标识；匿名为 {@code null}（按同一桶去重）
     * @param rawQuery 用户原始输入（内部按搜索口径归一化，与倒排键逐字一致）
     */
    public void record(String userKey, String rawQuery) {
        String word = SearchService.normalizeTag(rawQuery);
        if (word.isEmpty() || word.length() > MAX_WORD_LEN) {
            return;
        }
        String day = LocalDate.now(ZONE).format(DAY);
        try {
            String dedupKey = DEDUP_PREFIX + day + ":" + (userKey == null || userKey.isBlank() ? "anon" : userKey)
                    + ":" + word;
            Boolean first = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
            String hotKey = HOT_PREFIX + day;
            redis.opsForZSet().incrementScore(hotKey, word, 1d);
            // 每次都设 TTL：桶键只在首次写入时创建，这里顺带保证它一定会过期
            redis.expire(hotKey, DAY_TTL);
        } catch (Exception e) {
            // 旁路统计：统计失败不能影响搜索返回
            log.warn("热搜计数失败（忽略）: word={}, {}", word, e.getMessage());
        }
    }

    /**
     * 读取当前热搜榜（最近 {@value #WINDOW_DAYS} 天合并）。
     *
     * @param limit 条数（默认 10，上限 50）
     * @return 榜单；任何异常返回空榜单，不向前端暴露故障
     */
    public HotSearchResult top(int limit) {
        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Map<String, Double> merged = new HashMap<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = 0; i < WINDOW_DAYS; i++) {
            String key = HOT_PREFIX + today.minusDays(i).format(DAY);
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
        if (merged.isEmpty()) {
            return HotSearchResult.empty();
        }
        List<HotSearchResult.Item> items = new ArrayList<>(merged.size());
        for (Map.Entry<String, Double> e : merged.entrySet()) {
            items.add(new HotSearchResult.Item(e.getKey(), e.getValue()));
        }
        // 分数降序；同分按词字典序，保证同一份数据多次读取顺序稳定（前端不会莫名跳动）
        items.sort(Comparator.comparingDouble(HotSearchResult.Item::score).reversed()
                .thenComparing(HotSearchResult.Item::word));
        if (items.size() > n) {
            items = new ArrayList<>(items.subList(0, n));
        }
        return new HotSearchResult(items);
    }
}
