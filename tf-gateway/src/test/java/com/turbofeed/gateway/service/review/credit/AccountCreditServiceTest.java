package com.turbofeed.gateway.service.review.credit;

import com.turbofeed.gateway.repository.AccountCreditRepository;
import com.turbofeed.gateway.service.state.AccountStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心（P2-6 回归护栏）：{@link AccountCreditService} 的「软声誉」编排。
 *
 * <p>抖音式「信用是风险底盘」——ensure/getLevel 走 {@link AccountStateCache} 短 TTL 缓存，
 * 所有写路径（人审通过 / 违规扣分 / 申诉翻案）必须主动 {@code invalidate}，否则「自己改了自己看不见」。
 * 本测试锁定：</p>
 * <ul>
 *   <li>① 读路径（ensure / getLevel）经由 {@code stateCache.level}；</li>
 *   <li>② 三个写路径（onHumanApproved / onViolationConfirmed / onAppealUpheld）落库后必调
 *       {@code stateCache.invalidate(userId)}（P2-6 契约）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountCreditServiceTest {

    @Mock AccountCreditRepository accountCreditRepository;
    @Mock AccountStateCache stateCache;

    private AccountCreditService service() {
        return new AccountCreditService(accountCreditRepository, stateCache);
    }

    /** 让被 mock 的 stateCache.level 真正执行传入的 loader，从而覆盖信用读取分支。 */
    private void delegateLevel(long userId) {
        when(stateCache.level(eq(userId), any())).thenAnswer(
                inv -> ((Supplier<CreditLevel>) inv.getArgument(1)).get());
    }

    // ===== ① 读路径经由缓存 =====

    @Test
    void ensure_loadsLevelThroughCache_skipsNothingWhenCacheMiss() {
        long userId = 42L;
        delegateLevel(userId);
        when(accountCreditRepository.getLevel(userId)).thenReturn(CreditLevel.L1);

        assertEquals(CreditLevel.L1, service().ensure(userId));
        verify(accountCreditRepository).ensure(userId); // 缓存未命中 → 确保行存在
        verify(accountCreditRepository).getLevel(userId);
        verify(stateCache).level(eq(userId), any());
    }

    @Test
    void getLevel_returnsLevelFromCache() {
        long userId = 43L;
        delegateLevel(userId);
        when(accountCreditRepository.getLevel(userId)).thenReturn(CreditLevel.L2);

        assertEquals(CreditLevel.L2, service().getLevel(userId));
        verify(stateCache).level(eq(userId), any());
    }

    // ===== ② 写路径失效缓存契约（P2-6）=====

    @Test
    void onHumanApproved_invalidatesCacheWhenReleased() {
        long userId = 44L;
        when(accountCreditRepository.onHumanApproved(userId, 3)).thenReturn(true);

        assertTrue(service().onHumanApproved(userId, 3));
        verify(accountCreditRepository).onHumanApproved(userId, 3);
        verify(stateCache).invalidate(userId); // 观察期结束 → 缓存失效
    }

    @Test
    void onViolationConfirmed_deductsAndInvalidates() {
        long userId = 45L;

        service().onViolationConfirmed(userId);

        verify(accountCreditRepository).deduct(userId);
        verify(stateCache).invalidate(userId); // 扣分降级 → 缓存失效
    }

    @Test
    void onAppealUpheld_restoresAndInvalidates() {
        long userId = 46L;

        service().onAppealUpheld(userId);

        verify(accountCreditRepository).restore(userId);
        verify(stateCache).invalidate(userId); // 申诉翻案恢复 → 缓存失效
    }
}
