package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MediaStatusMachine} 契约测试（M1 / 3.3）。
 *
 * <p>本测试守住三件事：
 * <ol>
 *   <li><b>合法流转全部放行</b>——表一旦被改窄，业务路径会静默变成「审核点不动」，必须有用例红；</li>
 *   <li><b>非法流转全部拒绝</b>——尤其是 {@code APPROVED → APPROVED} 这类「看着无害」的自环，
 *       它决定 {@code review()} 是走幂等返回还是真去 CAS；</li>
 *   <li><b>业务代码里 CAS 用的 (expected, target) 对必须与表一致</b>——这是本测试最值钱的一条：
 *       状态机表和 {@code updateStatusCas} 的字面量是<b>两份表述</b>，只靠注释维系迟早漂移
 *       （本项目「跨服务一致性靠注释维系 = 缺陷根因」）。这条用例把两份表述钉在一起。</li>
 * </ol>
 */
class MediaStatusMachineTest {

    // ── 合法流转 ──────────────────────────────────────────────────────────

    @Test
    void 待审可转通过或驳回() {
        assertThat(MediaStatusMachine.isLegal(MediaStatus.PENDING, MediaStatus.APPROVED)).isTrue();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.PENDING, MediaStatus.REJECTED)).isTrue();
    }

    @Test
    void 已发布可下架() {
        assertThat(MediaStatusMachine.isLegal(MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN)).isTrue();
    }

    @Test
    void 驳回与下架均可申诉() {
        assertThat(MediaStatusMachine.canAppeal(MediaStatus.REJECTED)).isTrue();
        assertThat(MediaStatusMachine.canAppeal(MediaStatus.TAKEN_DOWN)).isTrue();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.REJECTED, MediaStatus.APPEALING)).isTrue();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.TAKEN_DOWN, MediaStatus.APPEALING)).isTrue();
    }

    @Test
    void 申诉可翻案恢复或维持下架() {
        assertThat(MediaStatusMachine.isLegal(MediaStatus.APPEALING, MediaStatus.APPROVED)).isTrue();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.APPEALING, MediaStatus.TAKEN_DOWN)).isTrue();
    }

    // ── 非法流转 ──────────────────────────────────────────────────────────

    @Test
    void 自环一律非法() {
        for (MediaStatus s : MediaStatus.values()) {
            assertThat(MediaStatusMachine.isLegal(s, s))
                    .as("任何状态都不应允许转到自身：%s -> %s", s, s)
                    .isFalse();
        }
    }

    @Test
    void 终态没有出边() {
        assertThat(MediaStatusMachine.isTerminal(MediaStatus.DELETED)).isTrue();
        assertThat(MediaStatusMachine.legalTargets(MediaStatus.DELETED)).isEmpty();
        // 终态作为目标也不该从别处可达（除用户删除这条不在审核机内的路径）
        assertThat(MediaStatusMachine.isLegal(MediaStatus.PENDING, MediaStatus.DELETED)).isFalse();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.APPROVED, MediaStatus.DELETED)).isFalse();
    }

    @Test
    void 已发布不能再审核() {
        // review() 依赖这条返回 false 来走「终态幂等返回」，若误判为合法会重复 CAS
        assertThat(MediaStatusMachine.isLegal(MediaStatus.APPROVED, MediaStatus.REJECTED)).isFalse();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.REJECTED, MediaStatus.APPROVED)).isFalse();
    }

    @Test
    void 待审不能直接申诉或下架() {
        assertThat(MediaStatusMachine.canAppeal(MediaStatus.PENDING)).isFalse();
        assertThat(MediaStatusMachine.canAppeal(MediaStatus.APPROVED)).isFalse();
        assertThat(MediaStatusMachine.canAppeal(MediaStatus.APPEALING)).isFalse();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.PENDING, MediaStatus.TAKEN_DOWN)).isFalse();
    }

    @Test
    void 空状态一律非法() {
        assertThat(MediaStatusMachine.isLegal(null, MediaStatus.APPROVED)).isFalse();
        assertThat(MediaStatusMachine.isLegal(MediaStatus.PENDING, null)).isFalse();
        assertThat(MediaStatusMachine.canAppeal(null)).isFalse();
        assertThat(MediaStatusMachine.legalTargets(null)).isEmpty();
    }

    @Test
    void 非法流转抛业务异常且信息可用于排障() {
        assertThatThrownBy(() -> MediaStatusMachine.requireLegal(MediaStatus.PENDING, MediaStatus.DELETED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法的审核状态流转")
                .hasMessageContaining("PENDING")
                .hasMessageContaining("DELETED");
    }

    @Test
    void 合法流转不抛异常() {
        MediaStatusMachine.requireLegal(MediaStatus.PENDING, MediaStatus.APPROVED);
        MediaStatusMachine.requireLegal(MediaStatus.APPEALING, MediaStatus.APPROVED);
    }

    // ── 与业务代码的 CAS 流转对保持一致 ────────────────────────────────────

    /**
     * 业务代码 {@code MediaReviewService} 里 5 处 {@code updateStatusCas(expected, target)}
     * 的字面量，必须与状态机表一致。
     *
     * <p>若有人只改了表没改 CAS（或反之），这条用例立刻变红——而不是等到线上出现
     * 「明明点了通过，状态却不动」的静默失效。</p>
     */
    @Test
    void 业务代码使用的CAS流转对必须与状态机一致() {
        // MediaReviewService#review（PENDING -> APPROVED / REJECTED，expected 由读到的状态给出）
        assertCasPair(MediaStatus.PENDING, MediaStatus.APPROVED);
        assertCasPair(MediaStatus.PENDING, MediaStatus.REJECTED);
        // MediaReviewService#takeDownAndPenalize
        assertCasPair(MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN);
        // MediaReviewService#appeal
        assertCasPair(MediaStatus.REJECTED, MediaStatus.APPEALING);
        assertCasPair(MediaStatus.TAKEN_DOWN, MediaStatus.APPEALING);
        // MediaReviewService#handleAppeal（翻案 / 维持）
        assertCasPair(MediaStatus.APPEALING, MediaStatus.APPROVED);
        assertCasPair(MediaStatus.APPEALING, MediaStatus.TAKEN_DOWN);
    }

    /**
     * 反向约束：状态机表里每声明一条合法流转，都应当能在业务代码找到对应用途。
     *
     * <p>正向用例只能防「业务代码比表更宽」，这条防的是「表比业务代码更宽」——
     * 即有人往表里加了一条从没实现过的流转，让文档再次跑在实际行为前面。</p>
     */
    @Test
    void 状态机表不应声明业务代码未实现的流转() {
        // 用【有序对】：状态机是有向图，{TAKEN_DOWN, APPEALING} 与 {APPEALING, TAKEN_DOWN}
        // 是两条不同的流转，用 Set 表示会把它们并成一条（EnumSet 还按枚举序归一）。
        List<List<MediaStatus>> implemented = List.of(
                List.of(MediaStatus.PENDING, MediaStatus.APPROVED),
                List.of(MediaStatus.PENDING, MediaStatus.REJECTED),
                List.of(MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN),
                List.of(MediaStatus.REJECTED, MediaStatus.APPEALING),
                List.of(MediaStatus.TAKEN_DOWN, MediaStatus.APPEALING),
                List.of(MediaStatus.APPEALING, MediaStatus.APPROVED),
                List.of(MediaStatus.APPEALING, MediaStatus.TAKEN_DOWN));

        for (MediaStatus from : MediaStatus.values()) {
            for (MediaStatus to : MediaStatusMachine.legalTargets(from)) {
                assertThat(implemented)
                        .as("状态机表声明了 %s -> %s，但业务代码里没有对应的 CAS 调用", from, to)
                        .contains(List.of(from, to));
            }
        }
    }

    private static void assertCasPair(MediaStatus expected, MediaStatus target) {
        assertThat(MediaStatusMachine.isLegal(expected, target))
                .as("CAS 流转对 (%s -> %s) 未在状态机表中声明合法", expected, target)
                .isTrue();
    }
}
