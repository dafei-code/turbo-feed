package com.turbofeed.gateway.service.penalty;

import com.turbofeed.gateway.repository.AccountPenaltyRepository;
import com.turbofeed.gateway.repository.ViolationRecordRepository;
import com.turbofeed.gateway.service.penalty.ViolationRecord;
import com.turbofeed.gateway.service.state.AccountStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心（P2-6 回归护栏）：{@link PenaltyService} 的「硬执行」编排。
 *
 * <p>覆盖三类易回归逻辑，全部纯 Mockito、不启 Spring、不需要 Docker：</p>
 * <ul>
 *   <li>① 写路径（recordViolation / liftPenalty）落库后必须调 {@code stateCache.invalidate(userId)}——
 *       这是 P2-6 把缓存委托给 tf-hotspot SDK 后，封禁/解除「自己改了自己能立刻看到」的契约；</li>
 *   <li>② liftPenalty 对「未封禁 / 无记录」早退且不触碰缓存与持久态；</li>
 *   <li>③ canWrite 的封禁推导（BANNED_PERM 必拒、BANNED_TEMP 按 ban_until 到期推导），
 *       逻辑藏在传给 {@code stateCache.canWrite} 的 loader 里，用委托桩把它真正执行出来。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PenaltyServiceTest {

    @Mock ViolationRecordRepository violationRecordRepository;
    @Mock AccountPenaltyRepository accountPenaltyRepository;
    @Mock PenaltyEscalationPolicy escalationPolicy;
    @Mock AccountStateCache stateCache;

    private PenaltyService service() {
        return new PenaltyService(violationRecordRepository, accountPenaltyRepository, escalationPolicy, stateCache);
    }

    private static PenaltyEscalationPolicy.Decision decision(PenaltyAction action, AccountPenaltyStatus status) {
        return new PenaltyEscalationPolicy.Decision(action, status, ViolationCategory.OTHER, "reason", null);
    }

    // ===== ① 写路径失效缓存契约 =====

    @Test
    void recordViolation_writesAuditAndPenaltyAndInvalidatesCache() {
        long userId = 42L;
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.empty());
        when(escalationPolicy.evaluate(any(), any(), anyInt(), any()))
                .thenReturn(decision(PenaltyAction.WARN, AccountPenaltyStatus.WARN));

        AccountPenalty next = service().recordViolation(userId, ViolationCategory.OTHER, ViolationSeverity.LOW,
                ViolationSource.HUMAN_REVIEW, "media/42/x", "reviewer", "warn");

        assertEquals(AccountPenaltyStatus.WARN, next.status());
        assertEquals(1, next.violationCount());
        verify(violationRecordRepository).insert(any(ViolationRecord.class));
        verify(accountPenaltyRepository).apply(eq(userId), any(AccountPenalty.class));
        verify(stateCache).invalidate(userId); // P2-6 契约：刚封禁自己下一个请求即见
    }

    @Test
    void recordViolation_criticalBan_marksBannedAndInvalidates() {
        long userId = 7L;
        Instant until = Instant.now().plus(Duration.ofDays(7));
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.empty());
        when(escalationPolicy.evaluate(any(), any(), anyInt(), any()))
                .thenReturn(new PenaltyEscalationPolicy.Decision(
                        PenaltyAction.BAN_TEMP, AccountPenaltyStatus.BANNED_TEMP, ViolationCategory.OTHER, "ban", until));

        AccountPenalty next = service().recordViolation(userId, ViolationCategory.OTHER, ViolationSeverity.CRITICAL,
                ViolationSource.MACHINE, null, "SYSTEM", "critical");

        assertEquals(AccountPenaltyStatus.BANNED_TEMP, next.status());
        verify(stateCache).invalidate(userId);
    }

    @Test
    void liftPenalty_bannedUser_liftsAndInvalidates() {
        long userId = 99L;
        AccountPenalty banned = new AccountPenalty(userId, AccountPenaltyStatus.BANNED_TEMP, ViolationCategory.OTHER,
                "reason", Instant.now().plus(Duration.ofDays(1)), 3, Instant.now(), Instant.now(), Instant.now());
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(banned));

        boolean lifted = service().liftPenalty(userId, ViolationSource.HUMAN_REVIEW, "reviewer", "appeal upheld");

        assertTrue(lifted);
        verify(accountPenaltyRepository).apply(eq(userId), argThat(p -> p.status() == AccountPenaltyStatus.NORMAL));
        verify(violationRecordRepository).insert(argThat(r -> r.actionTaken() == PenaltyAction.LIFT));
        verify(stateCache).invalidate(userId); // 解除后下一个请求即可写
    }

    // ===== ② liftPenalty 早退（不触碰缓存/持久态）=====

    @Test
    void liftPenalty_notBanned_returnsFalseAndDoesNotInvalidate() {
        long userId = 11L;
        AccountPenalty normal = new AccountPenalty(userId, AccountPenaltyStatus.NORMAL, null, null, null,
                0, null, null, Instant.now());
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(normal));

        assertFalse(service().liftPenalty(userId, ViolationSource.HUMAN_REVIEW, "reviewer", "nope"));
        verify(stateCache, never()).invalidate(anyLong());
        verify(accountPenaltyRepository, never()).apply(anyLong(), any());
        verify(violationRecordRepository, never()).insert(any());
    }

    @Test
    void liftPenalty_noRecord_returnsFalseAndDoesNotInvalidate() {
        long userId = 12L;
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.empty());

        assertFalse(service().liftPenalty(userId, ViolationSource.HUMAN_REVIEW, "reviewer", "nope"));
        verify(stateCache, never()).invalidate(anyLong());
    }

    // ===== ③ canWrite 封禁推导（loader 在 stateCache.canWrite 内执行）=====

    /** 让被 mock 的 stateCache.canWrite 真正执行传入的 loader，从而覆盖封禁推导分支。 */
    private void delegateCanWrite(long userId) {
        when(stateCache.canWrite(eq(userId), any())).thenAnswer(
                inv -> Boolean.TRUE.equals(((Supplier<Boolean>) inv.getArgument(1)).get()));
    }

    @Test
    void canWrite_permanentBan_returnsFalse() {
        long userId = 1L;
        delegateCanWrite(userId);
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(new AccountPenalty(userId,
                AccountPenaltyStatus.BANNED_PERM, null, null, null, 1, null, null, Instant.now())));

        assertFalse(service().canWrite(userId));
    }

    @Test
    void canWrite_tempBanActive_returnsFalse() {
        long userId = 2L;
        delegateCanWrite(userId);
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(new AccountPenalty(userId,
                AccountPenaltyStatus.BANNED_TEMP, ViolationCategory.OTHER, "r",
                Instant.now().plus(Duration.ofDays(1)), 1, null, null, Instant.now())));

        assertFalse(service().canWrite(userId));
    }

    @Test
    void canWrite_tempBanExpired_returnsTrue() {
        long userId = 3L;
        delegateCanWrite(userId);
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(new AccountPenalty(userId,
                AccountPenaltyStatus.BANNED_TEMP, ViolationCategory.OTHER, "r",
                Instant.now().minus(Duration.ofDays(1)), 1, null, null, Instant.now())));

        assertTrue(service().canWrite(userId));
    }

    @Test
    void canWrite_normal_returnsTrue() {
        long userId = 4L;
        delegateCanWrite(userId);
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.of(new AccountPenalty(userId,
                AccountPenaltyStatus.NORMAL, null, null, null, 0, null, null, Instant.now())));

        assertTrue(service().canWrite(userId));
    }

    @Test
    void canWrite_noRecord_returnsTrue() {
        long userId = 5L;
        delegateCanWrite(userId);
        when(accountPenaltyRepository.get(userId)).thenReturn(Optional.empty());

        assertTrue(service().canWrite(userId));
    }
}
