package com.turbofeed.gateway.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 搜索索引的<b>冷补重建</b>（S3 后半段）：Redis 索引丢了/过期了/上线前就没写，从库里重建。
 *
 * <h3>为什么不需要新建「冷补表」</h3>
 * <p>原计划给 S3 建两张 {@code !SINGLE} 表（{@code search_tag_index} / {@code search_user_index}）
 * 来存词表。实际读码后发现<b>这两张表是多余的副本</b>：
 * <ul>
 *   <li>话题侧：{@code media_tag}（128 分片，按 {@code post_id}）已经是标签的持久冷存，
 *       注释里就写明「可重建 Redis 索引」；再建一张同构表只是把同一份事实存两遍，
 *       并且要多维护一条写路径（多一处漏写就多一处不一致）；</li>
 *   <li>用户侧：{@code user} 表的 {@code nickname} 就是昵称的唯一事实源，
 *       没有任何「索引表」能比它更权威。</li>
 * </ul>
 * 所以这里做的是<b>重建作业</b>而不是新表——少两张表、少一条写路径，可重建性一点没少。</p>
 *
 * <h3>三种重建范围</h3>
 * <ol>
 *   <li>{@code tag-lexicon}：{@code SELECT DISTINCT tag FROM media_tag} → 词典 {@code tag:{tag}}。
 *       词典是<b>无 TTL</b> 的全局键，最容易在某次 Redis 迁移中整键丢失，丢了联想就全空。</li>
 *   <li>{@code user-index}：{@code user} 表有效用户 → 昵称索引 + 词典 {@code user:{nick}}。</li>
 *   <li>{@code tag-index}：{@code media_tag} 全部行 → 有序倒排 {@code tf:tag:media:z:{tag}}，
 *       score = {@code created_at}。<b>这是唯一能让「超过 7 天的老内容」重新可被搜到的手段</b>
 *       ——在线索引随分桶 TTL 一起过期，而搜索本身其实还能展示老内容（回查走 DB）。</li>
 * </ol>
 *
 * <h3>代价与边界（诚实记录）</h3>
 * <ul>
 *   <li>三条 {@code SELECT} 都<b>不带分片键</b>，ShardingSphere 会广播到 128 张物理表再合并。
 *       这是离线作业可以接受的代价；线上请求绝不能走这条路（这也是为什么它挂在
 *       {@code /api/admin/**} 而不是搜索接口里）。真到大数据量，应改为按分片游标并行导出。</li>
 *   <li>{@code tag-index} 用 {@code created_at} 做游标翻页。同一毫秒创建的相邻行如果正好
 *       卡在页边界，可能被漏掉——重跑一次即可补齐（ZADD 幂等）。</li>
 *   <li>重建出来的有序倒排给 30 天 TTL：它不像在线索引那样有写路径持续续期，必须留一个
 *       自清理窗口，否则内容删了、索引还在，搜索会持续捞出已删内容。</li>
 * </ul>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class SearchRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SearchRebuildService.class);

    /** 与引擎侧 {@code TagIndexService} 同源的有序倒排前缀（勿单边修改）。 */
    static final String TAG_ZSET_PREFIX = "tf:tag:media:z:";
    private static final String LEX_KEY = SearchIndexWriter.LEX_KEY;

    /** 重建出的有序倒排存活窗口（见类注释：它不会被在线写路径续期）。 */
    private static final Duration REBUILT_TTL = Duration.ofDays(30);
    /** 分页大小：既控制单次结果集内存占用，也控制单次 ZADD 的批大小。 */
    private static final int PAGE = 500;
    private static final int DEFAULT_MAX_ROWS = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final SearchIndexWriter indexWriter;

    public SearchRebuildService(JdbcTemplate jdbcTemplate,
                                StringRedisTemplate redis,
                                SearchIndexWriter indexWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.indexWriter = indexWriter;
    }

    /**
     * 重建话题联想词典（{@code tag-lexicon}）。
     *
     * @param dryRun 只统计不写入
     * @param maxRows 最多处理多少个去重后的标签
     */
    public RebuildReport rebuildTagLexicon(boolean dryRun, int maxRows) {
        int cap = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        long start = System.currentTimeMillis();
        List<String> tags;
        try {
            tags = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tag FROM media_tag LIMIT " + cap, String.class);
        } catch (RuntimeException e) {
            log.warn("重建话题词典失败（读 media_tag 出错）: {}", e.getMessage());
            return new RebuildReport("tag-lexicon", dryRun, 0, 0, 0, 0,
                    System.currentTimeMillis() - start, "读 media_tag 失败：" + e.getMessage());
        }
        int scanned = 0;
        int written = 0;
        int skipped = 0;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                skipped++;
                continue;
            }
            scanned++;
            if (dryRun) {
                continue;
            }
            try {
                // score 必须恒 0：词典要支持 ZRANGEBYLEX 前缀扫描（分数不齐就静默失效）
                redis.opsForZSet().add(LEX_KEY, SearchIndexWriter.LEX_TAG + tag, 0d);
                written++;
            } catch (RuntimeException e) {
                log.warn("写话题词典失败（跳过该词）: tag={}, {}", tag, e.getMessage());
            }
        }
        return new RebuildReport("tag-lexicon", dryRun, scanned, written, skipped, 0,
                System.currentTimeMillis() - start, null);
    }

    /**
     * 重建用户昵称索引 + 用户联想词（{@code user-index}）。
     *
     * @param dryRun 只统计不写入
     * @param maxRows 最多处理多少个用户
     */
    public RebuildReport rebuildUserIndex(boolean dryRun, int maxRows) {
        int cap = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, nickname FROM user WHERE status = 1 "
                            + "AND nickname IS NOT NULL AND nickname <> '' LIMIT " + cap);
        } catch (RuntimeException e) {
            log.warn("重建用户索引失败（读 user 出错）: {}", e.getMessage());
            return new RebuildReport("user-index", dryRun, 0, 0, 0, 0,
                    System.currentTimeMillis() - start, "读 user 失败：" + e.getMessage());
        }
        int scanned = 0;
        int written = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            Object nickObj = row.get("nickname");
            if (!(idObj instanceof Number id) || nickObj == null) {
                skipped++;
                continue;
            }
            String nickname = String.valueOf(nickObj);
            if (nickname.isBlank()) {
                skipped++;
                continue;
            }
            scanned++;
            if (dryRun) {
                continue;
            }
            // 复用在线写入口径：索引与词典一次写齐，避免重建出来的索引与注册写出的不一致
            indexWriter.indexUser(id.longValue(), nickname);
            written++;
        }
        return new RebuildReport("user-index", dryRun, scanned, written, skipped, 0,
                System.currentTimeMillis() - start, null);
    }

    /**
     * 从 {@code media_tag} 重建有序倒排（{@code tag-index}）——让老内容重新可搜。
     *
     * @param dryRun  只统计不写入
     * @param maxRows 本次最多处理多少行（按 created_at 倒序，优先补齐较新的内容）
     */
    public RebuildReport rebuildTagIndex(boolean dryRun, int maxRows) {
        int cap = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        long start = System.currentTimeMillis();

        int scanned = 0;
        int written = 0;
        int skipped = 0;
        long cursor = Long.MAX_VALUE;
        Set<String> touched = new LinkedHashSet<>();

        while (scanned < cap) {
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList(
                        "SELECT post_id, tag, created_at FROM media_tag WHERE created_at <= ? "
                                + "ORDER BY created_at DESC LIMIT " + PAGE, cursor);
            } catch (RuntimeException e) {
                log.warn("重建标签倒排中断（已写入部分不回滚）: {}", e.getMessage());
                return new RebuildReport("tag-index", dryRun, scanned, written, skipped, 0,
                        System.currentTimeMillis() - start, "读 media_tag 中断：" + e.getMessage());
            }
            if (rows.isEmpty()) {
                break;
            }
            Map<String, Set<ZSetOperations.TypedTuple<String>>> byTag = new LinkedHashMap<>();
            long lastCreated = cursor;
            for (Map<String, Object> row : rows) {
                String postId = str(row.get("post_id"));
                String tag = str(row.get("tag"));
                Object created = row.get("created_at");
                if (postId == null || tag == null || !(created instanceof Number c)) {
                    skipped++;
                    continue;
                }
                if (scanned >= cap) {
                    break;
                }
                scanned++;
                lastCreated = c.longValue();
                if (dryRun) {
                    continue;
                }
                byTag.computeIfAbsent(tag, k -> new LinkedHashSet<>())
                        .add(ZSetOperations.TypedTuple.of(postId, (double) c.longValue()));
            }
            if (!dryRun) {
                for (Map.Entry<String, Set<ZSetOperations.TypedTuple<String>>> e : byTag.entrySet()) {
                    try {
                        String zkey = TAG_ZSET_PREFIX + e.getKey();
                        redis.opsForZSet().add(zkey, e.getValue());
                        written += e.getValue().size();
                        touched.add(zkey);
                    } catch (RuntimeException ex) {
                        log.warn("写标签倒排失败（跳过该标签）: tag={}, {}", e.getKey(), ex.getMessage());
                    }
                }
            }
            // 不满一页 = 已取尽；满页则把游标推到上一毫秒继续（ZADD 幂等，边界重复无害）
            if (rows.size() < PAGE) {
                break;
            }
            long next = lastCreated - 1;
            if (next >= cursor) {
                break;
            }
            cursor = next;
        }

        if (!dryRun && !touched.isEmpty()) {
            // 只在原本没有 TTL 的键上补过期：已有 TTL 说明是在线写路径维护的，改它会打乱其寿命
            for (String zkey : touched) {
                try {
                    Long ttl = redis.getExpire(zkey);
                    if (ttl == null || ttl < 0) {
                        redis.expire(zkey, REBUILT_TTL);
                    }
                } catch (RuntimeException e) {
                    log.warn("补 TTL 失败（该键可能永不过期）: key={}, {}", zkey, e.getMessage());
                }
            }
        }
        return new RebuildReport("tag-index", dryRun, scanned, written, skipped,
                touched.size(), System.currentTimeMillis() - start, null);
    }

    /** 统一空值处理：DB 的 {@code VARCHAR} 取出来可能是 null。 */
    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    /**
     * 重建报告。
     *
     * @param scope     重建范围（tag-lexicon / user-index / tag-index）
     * @param dryRun    是否只统计未写入
     * @param scanned   扫描到的行/词数
     * @param written   实际写入的条数
     * @param skipped   因数据不合法跳过的条数
     * @param keys      涉及到的 Redis 键数（tag-index 为标签数）
     * @param elapsedMs 耗时
     * @param note      异常说明；正常为 {@code null}
     */
    public record RebuildReport(String scope,
                                boolean dryRun,
                                int scanned,
                                int written,
                                int skipped,
                                int keys,
                                long elapsedMs,
                                String note) {
    }
}
