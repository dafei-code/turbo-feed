package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 事务发件箱仓储（{@code outbox_event}，<b>单表</b>，落 ds_0）。
 *
 * <h3>它解决的问题（P0-3）</h3>
 * <p>「DB 事务」与「投递消息」是两个独立系统，没法用一个本地事务同时保证。改造前的写法是
 * <b>事务提交后再投递</b>（{@code afterCommit}）：DB 已提交、投递失败，这条消息就<b>永久丢失</b>了——
 * 内容已可见却没进公域时间线（fail-open）。反过来「先投递再提交」更糟：投递成功、事务回滚，
 * 时间线上出现一条 DB 里根本不存在的帖子。</p>
 *
 * <p>发件箱把问题换个问法：<b>不追求跨系统原子，只保证本地原子 + 至少一次投递</b>。
 * 事件行与业务数据写在<b>同一个本地事务</b>里——要么一起成功，要么一起回滚；
 * 提交后由一个中继（{@code OutboxRelay}）把行里的事件投出去，投失败就留着下次再投。
 * 于是「消息丢失」变成「消息可能重复」，而重复可以由消费端幂等消化
 * （本项目的 {@code FeedTimelinePublisher#append} 已是幂等：同一帖重复投递先摘旧位置再写新位置）。</p>
 *
 * <h3>状态机</h3>
 * <pre>
 *   PENDING ──领取(CAS)──▶ SENDING ──成功──▶ SENT(终态)
 *      ▲                      │
 *      │                      └──失败──▶ FAILED ──(退避后到期)──▶ 再次被领取
 *      │                                    │
 *      │                                    └──attempt_count ≥ max_attempts──▶ DEAD
 *      │                                                                    │
 *      │              【死信重投】OutboxDeadLetterSweeper 周期扫 DEAD            │
 *      │                dead_count &lt; 上限 → DEAD─reDeadLetter─▶ PENDING(长冷却)
 *      │                达上限            → 永久终态 + 持续高严重度告警
 *      └────── SENDING 卡死(进程崩溃) 超过 stuck-timeout ──────┘
 * </pre>
 *
 * <p><b>为什么要有 SENDING 这一态</b>：用 CAS 把「待投递」变成「投递中」，才能区分
 * 「正在投」与「该投了」——否则多实例中继会重复投递同一行。
 * 而 SENDING 卡死（进程在投的过程中挂了）由 {@link #reapStuck} 按超时回收，
 * 因此崩溃不会让事件永久悬停。</p>
 *
 * <p><b>DEAD 不再是真终点</b>：原状态机到 DEAD 即终止、既不复投也无告警（at-least-once 在 DEAD 处断裂）。
 * 现由 {@code OutboxDeadLetterSweeper} 周期把未达重投上限的 DEAD 行重新 open 成 PENDING（带冷却），
 * 交给既有 {@code OutboxRelay} 投递；判死瞬间由 {@code DeadLetterAlert} 触发告警
 * （{@code TIMELINE_REMOVE} 比 {@code TIMELINE_APPEND} 严重，下架内容持续展示是正向错误）。</p>
 *
 * <p><b>无 Lombok</b>：显式构造器（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Repository
public class OutboxEventRepository {

    /** 终态：已成功投递。 */
    public static final String STATUS_SENT = "SENT";
    /** 终态（重投前）：重试耗尽；未达重投上限前由死信扫描器重投，达上限后永久需人工对账。 */
    public static final String STATUS_DEAD = "DEAD";
    /** 中间态：已被某个中继/快路径领取，正在投递。 */
    public static final String STATUS_SENDING = "SENDING";
    /** 待投递：可被领取。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 投递失败：等 next_attempt_at 到期后可再次被领取。 */
    public static final String STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在<b>业务事务内</b>写一条待投递事件。
     *
     * <p>⚠️ 必须由调用方保证本方法与业务写在同一事务中——这是发件箱模式的全部价值所在
     * （见类注释）。单独调用它只会得到一条没人投递的行。</p>
     */
    public long insert(long id, OutboxEventType type, String aggregateId, Long userId,
                       String payload, int maxAttempts, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO outbox_event (id, event_type, aggregate_id, user_id, payload, status, "
                        + "attempt_count, max_attempts, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                id, type.name(), aggregateId, userId, payload, STATUS_PENDING,
                maxAttempts, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    public Optional<OutboxEvent> findById(long id) {
        List<OutboxEvent> list = jdbcTemplate.query(
                "SELECT id, event_type, aggregate_id, user_id, payload, status, attempt_count, "
                        + "max_attempts, next_attempt_at FROM outbox_event WHERE id = ?",
                (rs, rn) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("aggregate_id"),
                        (Long) rs.getObject("user_id"),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("next_attempt_at").toInstant()),
                id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 取出一批「到期待投递」的 id（不领锁，由 {@link #claim} 做 CAS）。 */
    public List<Long> findDueIds(int limit, Instant now) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM outbox_event WHERE status IN (?, ?) AND next_attempt_at <= ? ORDER BY id LIMIT ?",
                Long.class, STATUS_PENDING, STATUS_FAILED, Timestamp.from(now), limit);
    }

    /**
     * 领取一行（CAS：PENDING/FAILED → SENDING，并把 attempt_count +1）。
     *
     * @return true = 本调用者领到了这一行，可以投；false = 已被别人领走或状态已终态
     */
    public boolean claim(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = ?, attempt_count = attempt_count + 1, updated_at = ? "
                        + "WHERE id = ? AND status IN (?, ?)",
                STATUS_SENDING, Timestamp.from(now), id, STATUS_PENDING, STATUS_FAILED) == 1;
    }

    public void markSent(long id, Instant now) {
        jdbcTemplate.update("UPDATE outbox_event SET status = ?, last_error = NULL, updated_at = ? WHERE id = ?",
                STATUS_SENT, Timestamp.from(now), id);
    }

    /**
     * 投递失败：attempt_count 已在 {@link #claim} 时 +1，此处据它决定退避重试还是判死。
     *
     * @return true = 已判死（转 DEAD，触发死信告警）；false = 退回 FAILED 等待退避后重试
     */
    public boolean markFailed(long id, int attemptCount, int maxAttempts, String error,
                           long backoffSeconds, Instant now) {
        boolean dead = attemptCount >= maxAttempts;
        String nextStatus = dead ? STATUS_DEAD : STATUS_FAILED;
        Instant next = now.plusSeconds(dead ? 0 : Math.min(backoffSeconds * attemptCount, 300));
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = ?, last_error = ?, next_attempt_at = ?, updated_at = ? WHERE id = ?",
                nextStatus, trim(error, 500), Timestamp.from(next), Timestamp.from(now), id);
        return dead;
    }

    /**
     * 回收卡死的 SENDING（进程在投递途中崩溃，行永远停在 SENDING）：超时即退回 FAILED 等待重试。
     *
     * @return 回收的行数
     */
    public int reapStuck(long stuckTimeoutSeconds, Instant now) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = ?, next_attempt_at = ?, updated_at = ? "
                        + "WHERE status = ? AND updated_at < ?",
                STATUS_FAILED, Timestamp.from(now), Timestamp.from(now),
                STATUS_SENDING, Timestamp.from(now.minusSeconds(stuckTimeoutSeconds)));
    }

    /**
     * 取出一批「到期待重投」的死信 id（DEAD 且未达重投上限）。
     * 由 {@code OutboxDeadLetterSweeper} 周期调用，把 DEAD 重新 open 成 PENDING 交给既有中继投递。
     */
    public List<Long> findDeadIds(int limit, Instant now, int maxDeadRedeliveries) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM outbox_event WHERE status = ? AND dead_count < ? AND next_attempt_at <= ? ORDER BY id LIMIT ?",
                Long.class, STATUS_DEAD, maxDeadRedeliveries, Timestamp.from(now), limit);
    }

    /**
     * 死信重投：DEAD → PENDING，dead_count+1、attempt_count 清零（给满额重试预算）、next_attempt_at 推后冷却。
     *
     * <p>只负责「重新 open」，真正投递仍走既有 {@code OutboxRelay}（与正常路径同代码，不重复逻辑）。</p>
     *
     * @return 1 = 成功 reopen（可被中继重新领取）；0 = 已被别人处理或已非 DEAD
     */
    public int reDeadLetter(long id, long cooldownSeconds, Instant now) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = ?, dead_count = dead_count + 1, attempt_count = 0, "
                        + "next_attempt_at = ?, updated_at = ? WHERE id = ? AND status = ?",
                STATUS_PENDING, Timestamp.from(now.plusSeconds(cooldownSeconds)), Timestamp.from(now), id, STATUS_DEAD);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 发件箱行投影。 */
    public record OutboxEvent(long id, String eventType, String aggregateId, Long userId, String payload,
                              String status, int attemptCount, int maxAttempts, Instant nextAttemptAt) {
    }
}
