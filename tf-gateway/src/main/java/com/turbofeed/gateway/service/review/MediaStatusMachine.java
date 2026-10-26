package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 媒体审核状态机：<b>合法流转的唯一事实源</b>（M1 / 3.3）。
 *
 * <p><b>为什么要有它</b>：改造前，合法流转这件事散落在 {@link MediaReviewService} 的
 * 5 处 {@code updateStatusCas} 与 3 处 {@code if (cur != X)} 里，每处各写一遍「哪些前置态允许」。
 * 于是状态机<b>没有一处是权威的</b>：
 * <ul>
 *   <li>{@link MediaStatus} 的类注释至今仍写着「仅 PENDING 可转 APPROVED / REJECTED，
 *       终态不可再转换」——而 {@code TAKEN_DOWN → APPEALING}、{@code APPEALING → APPROVED}
 *       早已存在，文档与代码已经漂移；</li>
 *   <li>新增一个状态时，要人肉找出所有 {@code if} 补上，漏一处不报错，只表现为
 *       「某些路径下状态流转静默失效」。</li>
 * </ul>
 * 本类把「谁可以转到谁」收敛成<b>一张表</b>，业务代码只问
 * {@link #isLegal} / {@link #requireLegal}，不再自己拼 {@code cur != X && cur != Y}。</p>
 *
 * <p><b>当前状态图</b>（{@code DELETED} 是用户主动删除，不属于审核状态机，故无出边）：</p>
 * <pre>
 *   PENDING ──审核──▶ APPROVED ──下架──▶ TAKEN_DOWN ─┐
 *      └────审核────▶ REJECTED ──────────────────────┤
 *                                                    ├─申诉─▶ APPEALING
 *                        ┌───────────────────────────┘        │
 *                        └────────────────────────────────────┘
 *   APPEALING ──翻案──▶ APPROVED
 *   APPEALING ──维持──▶ TAKEN_DOWN
 * </pre>
 *
 * <p><b>注意：本表只描述「是否合法」，不替代 CAS</b>。并发下的正确性由
 * {@code updateStatusCas(expected, target)} 保证（只有真正完成翻转的一方才执行副作用）。
 * 本类的价值在于让「非法流转」<b>显式报错</b>，而不是让 CAS 静默地影响 0 行后由调用方各自定义
 * 「落空了算什么」——那是同一个歧义被写了 5 遍。</p>
 */
public final class MediaStatusMachine {

    /** 合法流转表：{@code from → 允许的 to 集合}。空集合 = 终态。 */
    private static final Map<MediaStatus, Set<MediaStatus>> LEGAL_TRANSITIONS = buildTransitions();

    private MediaStatusMachine() {
    }

    private static Map<MediaStatus, Set<MediaStatus>> buildTransitions() {
        Map<MediaStatus, Set<MediaStatus>> m = new EnumMap<>(MediaStatus.class);
        // 审核终裁：待审 → 通过 / 驳回。这是唯一有「分叉」的出边，由 approved 参数决定。
        m.put(MediaStatus.PENDING, EnumSet.of(MediaStatus.APPROVED, MediaStatus.REJECTED));
        // 已发布内容被举报/复审确认违规 → 下架停推。
        m.put(MediaStatus.APPROVED, EnumSet.of(MediaStatus.TAKEN_DOWN));
        // 被驳回 / 被下架 → 作者可申诉（进入人工复核，期间不可见）。
        m.put(MediaStatus.REJECTED, EnumSet.of(MediaStatus.APPEALING));
        m.put(MediaStatus.TAKEN_DOWN, EnumSet.of(MediaStatus.APPEALING));
        // 申诉终裁：翻案恢复公域 / 维持下架。
        m.put(MediaStatus.APPEALING, EnumSet.of(MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN));
        // 用户主动删除：终态，不属于审核流转。
        m.put(MediaStatus.DELETED, EnumSet.noneOf(MediaStatus.class));
        return Collections.unmodifiableMap(m);
    }

    /**
     * {@code from → to} 是否为合法流转。
     *
     * <p><b>null 安全</b>：{@code from} 为 null（记录不存在）一律返回 {@code false}——
     * 这样调用方可以写成 {@code if (!isLegal(cur, APPEALING)) throw ...}，把「查不到」和
     * 「状态不对」合并成同一条拒绝路径，不必先判 null 再判状态（当前 {@code appeal()} 就是这么
     * 分开写的，逻辑等价但更容易漏）。</p>
     */
    public static boolean isLegal(MediaStatus from, MediaStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<MediaStatus> targets = LEGAL_TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    /** 某状态下所有允许的后续状态（空集合表示终态）。用于日志与运维排查。 */
    public static Set<MediaStatus> legalTargets(MediaStatus from) {
        if (from == null) {
            return Collections.emptySet();
        }
        Set<MediaStatus> targets = LEGAL_TRANSITIONS.get(from);
        return targets == null ? Collections.emptySet() : Collections.unmodifiableSet(targets);
    }

    /** 是否终态（无任何出边）。 */
    public static boolean isTerminal(MediaStatus status) {
        return legalTargets(status).isEmpty();
    }

    /** 是否初始态（上传成功后的待审核）。 */
    public static boolean isInitial(MediaStatus status) {
        return status == MediaStatus.PENDING;
    }

    /** 是否可发起申诉（等价于 {@code isLegal(status, APPEALING)}，仅为调用点可读性）。 */
    public static boolean canAppeal(MediaStatus status) {
        return isLegal(status, MediaStatus.APPEALING);
    }

    /**
     * 非法流转即抛业务异常（{@link ErrorCode#PARAM_ERROR}）。
     *
     * <p><b>为什么要有「抛异常」版本</b>：{@link #isLegal} 适合「落空即幂等返回」的场景
     * （如重复审核），而申诉这类<b>用户主动发起</b>的操作，非法前置态应当明确报错——
     * 静默返回会让用户以为申诉已提交，实际什么都没发生。</p>
     */
    public static void requireLegal(MediaStatus from, MediaStatus to) {
        if (!isLegal(from, to)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "非法的审核状态流转: " + from + " -> " + to
                            + "（" + from + " 允许的后续状态: " + legalTargets(from) + "）");
        }
    }
}
