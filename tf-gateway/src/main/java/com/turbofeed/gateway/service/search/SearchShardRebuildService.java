package com.turbofeed.gateway.service.search;

import com.turbofeed.gateway.offline.OfflineShardJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 标签倒排的<b>按分片并行重建</b>（离线作业，替代逻辑表广播扫描）。
 *
 * <h3>为什么要另开这条路</h3>
 * <p>{@code SearchRebuildService#rebuildTagIndex} 走的是逻辑表 {@code media_tag}：
 * 查询不带分片键 → ShardingSphere <b>广播 128 张物理表</b>再归并排序，
 * 每一页都要把 128 份结果拉回来重排，数据量大时既慢又吃内存，而且是<b>单线程</b>。</p>
 *
 * <p>本服务直连物理库（{@link OfflineShardJdbc}），把工作拆成 128 个独立分片任务：
 * <ul>
 *   <li>每片用<b>主键游标</b>（{@code post_id > ? ORDER BY post_id}）顺序扫，走主键索引，
 *       不做全表排序；</li>
 *   <li>多片<b>并行</b>（并发可配，上限 16），互不干扰；</li>
 *   <li>每片<b>独立断点</b>：进度写 Redis，中途失败后 {@code resume=true} 可从断点续跑，
 *       不必从头再来。</li>
 * </ul></p>
 *
 * <h3>三个容易做错的地方</h3>
 * <ol>
 *   <li><b>不能用时间做游标</b>（{@code created_at} 无索引）→ 每页全表排序，比广播还慢。
 *       用主键顺序，反正 ZSET 自己会按 score 排，写入顺序无所谓。</li>
 *   <li><b>并发不是越大越好</b>：物理库连接数与 CPU 有限，超过一定并发只会互相拖慢。
 *       上限 16，默认 4。</li>
 *   <li><b>断点必须按分片存</b>：存一个全局进度没有意义——128 片是各自推进的，
 *       全局进度无法表达"第 37 片已经扫到哪"。</li>
 * </ol>
 *
 * <h3>可用性</h3>
 * <p>依赖 {@link OfflineShardJdbc}，而它只有在配了
 * {@code turbofeed.search.rebuild.offline.ds0-url} 时才装配。未配置时
 * {@link #available()} 返回 {@code false}，调用方应回退到
 * {@code SearchRebuildService#rebuildTagIndex}（逻辑表广播版），而不是直接失败。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class SearchShardRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SearchShardRebuildService.class);

    /** 与引擎侧 {@code TagIndexService} 同源的有序倒排前缀（勿单边修改）。 */
    static final String TAG_ZSET_PREFIX = "tf:tag:media:z:";
    /** 断点键：{@code tf:ops:rebuild:ck:{shard}} → 该片已扫到的最后一个 post_id。 */
    static final String CK_PREFIX = "tf:ops:rebuild:ck:";
    /** 断点保留 7 天：够完成一次重建 + 留排查窗口，又不会永久占着键空间。 */
    private static final Duration CK_TTL = Duration.ofDays(7);
    /** 重建出的倒排存活窗口（同逻辑表版：它没有在线写路径续期，必须自带过期）。 */
    private static final Duration REBUILT_TTL = Duration.ofDays(30);

    /** 物理分片数（与 {@code hashMod128} 对齐）。 */
    static final int SHARD_COUNT = 128;
    /** 每片每页行数。 */
    static final int PAGE = 500;
    /** 并发上限：超过这个数只会让物理库互相拖慢。 */
    private static final int MAX_PARALLELISM = 16;
    private static final int DEFAULT_PARALLELISM = 4;

    private final OfflineShardJdbc shardJdbc;
    private final StringRedisTemplate redis;

    public SearchShardRebuildService(ObjectProvider<OfflineShardJdbc> provider, StringRedisTemplate redis) {
        this.shardJdbc = provider.getIfAvailable();
        this.redis = redis;
    }

    /** 离线分片通道是否可用（未配置直连数据源时为 false）。 */
    public boolean available() {
        return shardJdbc != null;
    }

    /**
     * 并行重建全部 128 个分片。
     *
     * @param dryRun      只统计不写入
     * @param parallelism 并发度（默认 4，上限 16）
     * @param resume      从上次断点继续（跑完的片会自动清除断点）
     */
    public SearchRebuildService.RebuildReport rebuild(boolean dryRun, int parallelism, boolean resume) {
        long start = System.currentTimeMillis();
        if (!available()) {
            return new SearchRebuildService.RebuildReport("tag-index-sharded", dryRun, 0, 0, 0, 0, 0,
                    System.currentTimeMillis() - start,
                    "未配置 turbofeed.search.rebuild.offline.ds0-url：离线分片通道不可用，"
                            + "请回退 scope=tag-index（逻辑表广播版）");
        }
        int p = Math.min(Math.max(parallelism, 1), MAX_PARALLELISM);
        ExecutorService pool = Executors.newFixedThreadPool(p);
        try {
            List<Future<ShardStat>> futures = new ArrayList<>(SHARD_COUNT);
            for (int i = 0; i < SHARD_COUNT; i++) {
                final int shard = i;
                futures.add(pool.submit(() -> scanShard(shard, dryRun, resume)));
            }
            int scanned = 0;
            int written = 0;
            int done = 0;
            int failed = 0;
            for (Future<ShardStat> f : futures) {
                try {
                    ShardStat s = f.get();
                    scanned += s.scanned();
                    written += s.written();
                    done++;
                } catch (Exception e) {
                    // 单片失败不影响其余片；断点已存，resume=true 重跑即可补上
                    failed++;
                    log.warn("分片重建失败（可 resume 续跑）: {}", e.getMessage());
                }
            }
            String note = "分片 " + done + "/" + SHARD_COUNT + " 完成，并发 " + p
                    + (failed > 0 ? "，失败 " + failed + " 片（resume=true 续跑）" : "");
            return new SearchRebuildService.RebuildReport("tag-index-sharded", dryRun, scanned, written,
                    0, 0, done, System.currentTimeMillis() - start, note);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 扫完一个物理分片：主键游标分页 → 按标签攒批 → ZADD → 存断点 → 跑完清断点。 */
    private ShardStat scanShard(int shard, boolean dryRun, boolean resume) {
        String ckKey = CK_PREFIX + shard;
        String cursor = null;
        if (resume) {
            try {
                cursor = redis.opsForValue().get(ckKey);
            } catch (RuntimeException e) {
                log.warn("读取分片断点失败（从头扫描）: shard={}, {}", shard, e.getMessage());
            }
        }
        int scanned = 0;
        int written = 0;
        Set<String> touched = new LinkedHashSet<>();
        while (true) {
            List<OfflineShardJdbc.Row> rows = shardJdbc.page(shard, cursor, PAGE);
            if (rows.isEmpty()) {
                break;
            }
            Map<String, Set<ZSetOperations.TypedTuple<String>>> byTag = new LinkedHashMap<>();
            for (OfflineShardJdbc.Row r : rows) {
                if (r.postId() == null || r.postId().isBlank() || r.tag() == null || r.tag().isBlank()) {
                    continue;
                }
                scanned++;
                cursor = r.postId();
                if (dryRun) {
                    continue;
                }
                byTag.computeIfAbsent(r.tag(), k -> new LinkedHashSet<>())
                        .add(ZSetOperations.TypedTuple.of(r.postId(), (double) r.createdAt()));
            }
            if (!dryRun) {
                for (Map.Entry<String, Set<ZSetOperations.TypedTuple<String>>> e : byTag.entrySet()) {
                    String zkey = TAG_ZSET_PREFIX + e.getKey();
                    redis.opsForZSet().add(zkey, e.getValue());
                    written += e.getValue().size();
                    touched.add(zkey);
                }
                saveCheckpoint(ckKey, cursor);
            }
            if (rows.size() < PAGE) {
                break;
            }
        }
        if (!dryRun) {
            try {
                redis.delete(ckKey);
            } catch (RuntimeException e) {
                log.warn("清理分片断点失败（下次 resume 会多扫一页，无害）: shard={}, {}", shard, e.getMessage());
            }
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
        return new ShardStat(scanned, written);
    }

    private void saveCheckpoint(String ckKey, String cursor) {
        if (cursor == null) {
            return;
        }
        try {
            redis.opsForValue().set(ckKey, cursor, CK_TTL);
        } catch (RuntimeException e) {
            log.warn("写分片断点失败（续跑时该片会重扫，无害）: key={}, {}", ckKey, e.getMessage());
        }
    }

    /** 单片统计。 */
    private record ShardStat(int scanned, int written) {
    }
}
