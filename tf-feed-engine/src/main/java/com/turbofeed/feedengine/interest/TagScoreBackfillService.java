package com.turbofeed.feedengine.interest;

import com.turbofeed.feedengine.timeline.FeedTimelineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 存量标签倒排的<b>分数回填</b>（一次性运维作业，可重复执行）。
 *
 * <h3>要解决的问题</h3>
 * <p>标签倒排有两套：SET（{@code tf:tag:media:{tag}}，兴趣召回用）与 ZSET
 * （{@code tf:tag:media:z:{tag}}，搜索翻页用）。ZSET 是搜索上线时才补的，
 * 之前入流的内容<b>只在 SET 里</b>。搜索读的是 ZSET，于是
 * 「比搜索功能更早发布的老帖一律搜不到」——功能上线即带病，且<b>不报错、不告警</b>。</p>
 *
 * <h3>为什么回填不需要碰数据库</h3>
 * <p>分数 = 入流时刻（{@code approvedAt} 毫秒），它已经在 Redis 里：反查索引
 * {@code tf:feed:idx:{timelineKey}} → {@code (桶 key, 成员串)} → {@code ZSCORE} 即得
 * （见 {@link FeedTimelineStore#inflowScoreOf(String)}）。因此整个回填是
 * <b>纯 Redis 的点查 + 批量 ZADD</b>：不做 128 分片广播、不读 media_tag、
 * 也不会因为「库里的 created_at 与入流时间不等」而写出与实际排序矛盾的分数。</p>
 *
 * <h3>为什么必须在 SET 侧驱动</h3>
 * <p>存量内容的清单只有 SET 有（总得有个地方记着「这个标签下有这些帖」）。以 SET 成员
 * 集合减去 ZSET 已有成员，差集就是待回填集合——这个差集天然幂等：重复跑只是把已有分数
 * 再写一遍。</p>
 *
 * <h3>已知边界（诚实记录）</h3>
 * <ul>
 *   <li>反查索引与 SET 同 TTL（7d 量级），<b>两边一起过期</b>：超过 7 天的内容既不在 SET
 *       也不在时间线里，回填无从下手——那部分要靠 {@code /api/admin/search/rebuild}
 *       的 {@code tag-index} 从 {@code media_tag} 冷源重建。</li>
 *   <li>{@code SCAN} 是渐进遍历，不是快照：作业运行期间新入流的内容可能漏扫一两个标签键，
 *       但它们的 ZSET 本来就由在线写路径维护，不影响正确性。</li>
 *   <li>Redis Cluster 下 {@code SCAN} 的节点覆盖由 Spring Data Redis 处理；本机是单节点，
 *       大集群上建议按节点分片跑（参数 {@code maxKeys} 支持分批）。</li>
 * </ul>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class TagScoreBackfillService {

    private static final Logger log = LoggerFactory.getLogger(TagScoreBackfillService.class);

    /** 标签 → 内容集合（回填的驱动侧，与 {@link TagIndexService} 同源，勿单边修改）。 */
    private static final String TAG_SET_PREFIX = "tf:tag:media:";
    /** 标签 → 内容有序集合（回填的目标侧）。 */
    private static final String TAG_ZSET_PREFIX = "tf:tag:media:z:";

    /** 扫描批大小：只影响 SCAN 的每次往返条数，不影响结果完整性。 */
    private static final int SCAN_BATCH = 500;
    /** 默认最多处理多少个标签键（防一次跑太久；可重复执行续跑）。 */
    private static final int DEFAULT_MAX_KEYS = 2000;
    /** 与 {@link TagIndexService} 的 TTL 对齐：新建出来的 ZSET 键必须有过期时间。 */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final FeedTimelineStore timelineStore;

    public TagScoreBackfillService(StringRedisTemplate redis, FeedTimelineStore timelineStore) {
        this.redis = redis;
        this.timelineStore = timelineStore;
    }

    /**
     * 执行回填。
     *
     * @param dryRun 只统计不写入（默认 true：先看清楚要改什么，再决定是否落）
     * @param maxKeys 本次最多处理多少个标签键；{@code <=0} 用默认值
     * @return 统计报告（可直接回给运维接口的调用方）
     */
    public BackfillReport backfill(boolean dryRun, int maxKeys) {
        int cap = maxKeys <= 0 ? DEFAULT_MAX_KEYS : maxKeys;
        long start = System.currentTimeMillis();

        int scannedTags = 0;
        int scannedMembers = 0;
        int existingScored = 0;
        int filled = 0;
        int unresolved = 0;
        int failedKeys = 0;
        List<String> samples = new ArrayList<>(10);

        ScanOptions options = ScanOptions.scanOptions().match(TAG_SET_PREFIX + "*").count(SCAN_BATCH).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                if (scannedTags >= cap) {
                    break;
                }
                String key = cursor.next();
                // tf:tag:media:z:{tag} 也会被上一个 pattern 命中，必须排除，否则会把有序索引当标签名再套一层
                if (key == null || key.startsWith(TAG_ZSET_PREFIX)) {
                    continue;
                }
                String tag = key.substring(TAG_SET_PREFIX.length());
                if (tag.isBlank()) {
                    continue;
                }
                scannedTags++;
                try {
                    TagStat stat = backfillOne(tag, dryRun);
                    scannedMembers += stat.members();
                    existingScored += stat.scored();
                    filled += stat.filled();
                    unresolved += stat.unresolved();
                    if (stat.filled() > 0 && samples.size() < 10) {
                        samples.add(tag + "(" + stat.filled() + "/" + stat.members() + ")");
                    }
                } catch (Exception e) {
                    // 单个标签失败不影响其余：回填是幂等的，下次重跑即可修好这一批
                    failedKeys++;
                    log.warn("标签回填失败（跳过，可重跑）: tag={}, {}", tag, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("标签回填扫描中断（已处理部分不回滚）: {}", e.getMessage());
        }

        return new BackfillReport(dryRun, scannedTags, scannedMembers, existingScored, filled,
                unresolved, failedKeys, samples, System.currentTimeMillis() - start);
    }

    /** 单标签回填：SET 成员 − ZSET 已有 → 逐个解析入流时刻 → 批量 ZADD。 */
    private TagStat backfillOne(String tag, boolean dryRun) {
        String setKey = TAG_SET_PREFIX + tag;
        String zsetKey = TAG_ZSET_PREFIX + tag;

        Set<String> members = redis.opsForSet().members(setKey);
        if (members == null || members.isEmpty()) {
            return new TagStat(0, 0, 0, 0);
        }
        Set<String> scored = new HashSet<>();
        Set<ZSetOperations.TypedTuple<String>> existing =
                redis.opsForZSet().rangeWithScores(zsetKey, 0, -1);
        if (existing != null) {
            for (ZSetOperations.TypedTuple<String> t : existing) {
                if (t.getValue() != null) {
                    scored.add(t.getValue());
                }
            }
        }

        Set<ZSetOperations.TypedTuple<String>> toAdd = new HashSet<>();
        int unresolved = 0;
        for (String member : members) {
            if (member == null || scored.contains(member)) {
                continue;
            }
            Long score = timelineStore.inflowScoreOf(member);
            if (score == null) {
                // 反查索引已过期 = 该内容已不在可服务窗口，补了也点不开
                unresolved++;
                continue;
            }
            toAdd.add(ZSetOperations.TypedTuple.of(member, (double) score));
        }

        boolean created = existing == null || existing.isEmpty();
        if (!dryRun && !toAdd.isEmpty()) {
            redis.opsForZSet().add(zsetKey, toAdd);
            // 新建/已无 TTL 的键要补过期时间：否则回填会凭空造出一个永不过期的索引，
            // 内容下架后成员清不掉就一直能被搜到
            Long ttl = redis.getExpire(zsetKey);
            if (ttl == null || ttl < 0) {
                redis.expire(zsetKey, TTL);
            }
        }
        return new TagStat(members.size(), scored.size(), toAdd.size(), unresolved);
    }

    /** 单标签统计（内部）。 */
    private record TagStat(int members, int scored, int filled, int unresolved) {
    }

    /**
     * 回填报告。
     *
     * @param dryRun         是否只统计未写入
     * @param scannedTags    扫描到的标签键数
     * @param scannedMembers 这些标签下的成员总数（含已有序号的）
     * @param existingScored 已有序号的成员数（无需回填）
     * @param filled         本次补上序号的成员数
     * @param unresolved     解析不到入流时刻的成员数（索引已过期，跳过）
     * @param failedKeys     处理失败的标签键数（可重跑修复）
     * @param samples        填充量最大的若干标签，便于人工抽查
     * @param elapsedMs      耗时
     */
    public record BackfillReport(boolean dryRun,
                                 int scannedTags,
                                 int scannedMembers,
                                 int existingScored,
                                 int filled,
                                 int unresolved,
                                 int failedKeys,
                                 List<String> samples,
                                 long elapsedMs) {
    }
}
