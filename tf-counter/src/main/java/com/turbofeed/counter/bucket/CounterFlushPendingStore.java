package com.turbofeed.counter.bucket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * flush 失败快照暂存（PR-E B2 三段式的「独立失败暂存」）。
 *
 * <p><b>为什么是独立表而不是把增量还回热缓冲</b>：{@link CounterBufferService} /
 * {@link RedisCounterBuffer} 的缓冲没有批次身份，且 {@code applyBatch} 是
 * {@code +VALUES()} 累加 UPSERT。失败快照一旦还回去，下个周期会用<b>新 batchId</b>落库，
 * {@code counter_flush_dedup} 完全拦不住，结果是重复累加。
 * 只有把「整份快照 + 原 batchId」一起持久化，重放时命中 dedup 才是真正幂等。</p>
 *
 * <p>存在自有库（prod 是共享 MySQL，本地是 H2 MySQL 模式），多副本共享，
 * 因此任意副本都可在下个 flush 周期或重启后接过别人的失败批次。</p>
 *
 * <p><b>与热缓冲隔离</b>：本类只做「存 / 取 / 删 / 报告」，不参与 {@code add}，
 * 不持有内存副本；flush 周期会先重放暂存再 drain 新增量。</p>
 */
@Repository
public class CounterFlushPendingStore {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushPendingStore.class);
    private static final int DRY_RUN_MAX_BATCHES = 1000;
    private static final int MAX_ERROR_LEN = 512;

    private static final String SELECT_COLUMNS =
            "batch_id, payload, attempts, created_at, last_error";

    private static final TypeReference<Map<String, List<Number>>> DELTAS_JSON_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CounterFlushPendingStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记一个失败批次。同一 batchId 重复写入只累加 attempts，不覆盖原快照
     * （原快照是重放的唯一依据，绝不能被后来的空/部分数据替换）。
     */
    public void save(CounterFlushSnapshot snapshot, String error) {
        String payload = encode(snapshot.deltas());
        String err = truncate(error);
        int updated = jdbcTemplate.update(
                "UPDATE counter_flush_pending SET attempts = attempts + 1, last_error = ? WHERE batch_id = ?",
                err, snapshot.batchId());
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO counter_flush_pending (batch_id, payload, attempts, created_at, last_error) "
                            + "VALUES (?, ?, 1, ?, ?)",
                    snapshot.batchId(), payload, Timestamp.from(Instant.now()), err);
        } catch (DuplicateKeyException e) {
            // 并发副本同时登记同一批次：另一条已写入，原快照保留，忽略本次插入
            log.warn("失败批次已被并发副本登记，跳过重复插入: batchId={}", snapshot.batchId());
        }
    }

    /** 按创建时间取最老的一批待重放快照（每周期限量，避免一次卡死 flush）。 */
    public List<PendingBatch> loadOldest(int limit) {
        List<PendingBatch> out = new ArrayList<>(jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM counter_flush_pending ORDER BY created_at ASC LIMIT ?",
                (rs, i) -> {
                    String batchId = rs.getString("batch_id");
                    try {
                        Map<String, long[]> deltas = decode(rs.getString("payload"));
                        Timestamp ts = rs.getTimestamp("created_at");
                        return new PendingBatch(batchId, new CounterFlushSnapshot(batchId, deltas),
                                rs.getInt("attempts"),
                                ts == null ? Instant.EPOCH : ts.toInstant(),
                                rs.getString("last_error"));
                    } catch (Exception e) {
                        log.error("失败批次快照损坏，跳过自动重放（需人工对账）: batchId={}, {}", batchId, e.toString());
                        return null;
                    }
                }, Math.max(1, limit)));
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    /** 重放成功后删除；必须复用原 batchId 落库成功后才调用。 */
    public void delete(String batchId) {
        jdbcTemplate.update("DELETE FROM counter_flush_pending WHERE batch_id = ?", batchId);
    }

    /** 重放仍失败：只累加次数与错误，快照保持原样。 */
    public void touch(String batchId, String error) {
        jdbcTemplate.update(
                "UPDATE counter_flush_pending SET attempts = attempts + 1, last_error = ? WHERE batch_id = ?",
                truncate(error), batchId);
    }

    /**
     * B4 对账 dry-run：只报告「尚未落库的增量」，不改任何数据、不触发重放。
     *
     * <p>用途：突发期 / DB 抖动后，运营或 SRE 能直接看到有多少批次、多少帖子、
     * 各维度合计增量还卡在暂存里，作为是否需要人工介入、以及重建后核对的依据。</p>
     */
    public DryRunReport dryRun() {
        List<PendingBatch> batches = loadOldest(DRY_RUN_MAX_BATCHES);
        long[] totals = new long[4];
        int posts = 0;
        List<BatchLine> lines = new ArrayList<>();
        for (PendingBatch b : batches) {
            Map<String, long[]> deltas = b.snapshot().deltas();
            posts += deltas.size();
            for (long[] v : deltas.values()) {
                for (int i = 0; i < Math.min(4, v.length); i++) {
                    totals[i] += v[i];
                }
            }
            lines.add(new BatchLine(b.batchId(), deltas.size(), b.attempts(),
                    b.createdAt().toString(), b.lastError()));
        }
        return new DryRunReport(batches.size(), posts, totals, lines,
                "dry-run 只读：列出尚未落库的 flush 批次，不改数据；重放由下个 flush 周期自动执行");
    }

    private String encode(Map<String, long[]> deltas) {
        try {
            return objectMapper.writeValueAsString(deltas);
        } catch (Exception e) {
            throw new IllegalStateException("失败批次快照序列化失败", e);
        }
    }

    /** 反序列化走 {@code Map<String, List<Number>>} 中间态，避开 Jackson 对泛型数组的还原差异。 */
    private Map<String, long[]> decode(String json) throws Exception {
        Map<String, List<Number>> raw = objectMapper.readValue(json, DELTAS_JSON_TYPE);
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Number>> e : raw.entrySet()) {
            long[] arr = new long[4];
            List<Number> v = e.getValue();
            if (v != null) {
                for (int i = 0; i < Math.min(4, v.size()); i++) {
                    arr[i] = v.get(i) == null ? 0L : v.get(i).longValue();
                }
            }
            out.put(e.getKey(), arr);
        }
        return out;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LEN ? error : error.substring(0, MAX_ERROR_LEN);
    }

    /** 一条待重放的失败批次。 */
    public record PendingBatch(String batchId, CounterFlushSnapshot snapshot,
                               int attempts, Instant createdAt, String lastError) {
    }

    /** dry-run 报告里的单行摘要。 */
    public record BatchLine(String batchId, int postCount, int attempts,
                            String createdAt, String lastError) {
    }

    /** dry-run 差异报告（只读）。 */
    public record DryRunReport(int pendingBatches, int pendingPosts, long[] pendingDeltas,
                               List<BatchLine> batches, String note) {
    }
}
