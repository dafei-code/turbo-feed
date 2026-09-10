package com.turbofeed.gateway.service.review.credit;

import com.turbofeed.gateway.repository.AccountCreditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号信用服务：封装信用行的「确保 / 读取 / 违规扣分 / 申诉加回」，
 * 供 {@code MediaReviewService#handleUploaded} 决定先发后审还是先审后放。
 *
 * <p>抖音式「信用是风险底盘」：高信用账号先发后审（发布即进流量池），低信用/新号/近 30 天有下架
 * 的账号先审后放（机审通过仍卡人审闸），差异由本服务统一给出 {@link CreditLevel}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCreditService {

    private final AccountCreditRepository accountCreditRepository;

    /** 确保信用行存在并返回当前等级（无记录则默认 L1 普通）。 */
    public CreditLevel ensure(long userId) {
        accountCreditRepository.ensure(userId);
        return accountCreditRepository.getLevel(userId);
    }

    /** 读取当前等级（strict_queue 强制降 L0）。 */
    public CreditLevel getLevel(long userId) {
        return accountCreditRepository.getLevel(userId);
    }

    /** 违规确认（举报成立 / 人审驳回）：扣分降级，并置加严队列。 */
    public void onViolationConfirmed(long userId) {
        accountCreditRepository.deduct(userId);
        log.info("账号信用扣分（违规确认）: userId={}, level={}", userId, getLevel(userId));
    }

    /** 申诉翻案：加分恢复等级。 */
    public void onAppealUpheld(long userId) {
        accountCreditRepository.restore(userId);
        log.info("账号信用恢复（申诉翻案）: userId={}, level={}", userId, getLevel(userId));
    }
}
