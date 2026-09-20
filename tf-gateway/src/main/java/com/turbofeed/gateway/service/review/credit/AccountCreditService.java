package com.turbofeed.gateway.service.review.credit;

import com.turbofeed.gateway.repository.AccountCreditRepository;
import com.turbofeed.gateway.service.state.AccountStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号信用服务：封装信用行的「确保 / 读取 / 违规扣分 / 申诉加回」，
 * 供 {@code MediaReviewService#handleUploaded} 决定先发后审还是先审后放。
 *
 * <p>抖音式「信用是风险底盘」：高信用账号先发后审（发布即进流量池），低信用/新号/近 30 天有下架
 * 的账号先审后放（机审通过仍卡人审闸），差异由本服务统一给出 {@link CreditLevel}。</p>
 *
 * <p><b>读取走本地缓存（changelog 0039）</b>：{@link #ensure} 挂在每次上传的热路径上，
 * 改造前每次上传都要一次 {@code INSERT ... ON DUPLICATE KEY} + 一次 SELECT。
 * 信用是「账号级、低频变更」数据，改由 {@link AccountStateCache} 短 TTL 缓存；
 * <b>所有写路径（扣分 / 恢复 / 观察期计数）都主动失效缓存</b>，不存在「自己改了自己看不见」。</p>
 *
 * <p><b>无 Lombok</b>：显式声明日志与构造器（本机构建环境对被修改文件的 Lombok 注解处理不生效）。</p>
 */
@Service
public class AccountCreditService {

    private static final Logger log = LoggerFactory.getLogger(AccountCreditService.class);

    private final AccountCreditRepository accountCreditRepository;
    private final AccountStateCache stateCache;

    public AccountCreditService(AccountCreditRepository accountCreditRepository, AccountStateCache stateCache) {
        this.accountCreditRepository = accountCreditRepository;
        this.stateCache = stateCache;
    }

    /**
     * 确保信用行存在并返回当前等级（无记录则默认 L1 普通）。
     *
     * <p>缓存命中时<b>连 INSERT 一起跳过</b>：命中即说明该账号此前已 ensure 过（行必然已存在），
     * 再去补一次 {@code ON DUPLICATE KEY} 纯属浪费。这也是本次缓存改造最大的一笔收益——
     * 上传热路径上原本每帖必有一次写。</p>
     */
    public CreditLevel ensure(long userId) {
        return stateCache.level(userId, () -> {
            accountCreditRepository.ensure(userId);
            return accountCreditRepository.getLevel(userId);
        });
    }

    /** 读取当前等级（违规加严 or 新人观察期都会强制降 L0）。 */
    public CreditLevel getLevel(long userId) {
        return stateCache.level(userId, () -> accountCreditRepository.getLevel(userId));
    }

    /**
     * 人审通过一帖（只能由人工审核路径调用）：累加新人观察期计数，达阈值即转正常分级。
     *
     * <p>阈值来自 {@code turbofeed.media.review.new-user-approve-threshold}，由调用方传入，
     * 便于测试注入与后续按环境调整。返回是否在本次调用中解除了观察期。</p>
     */
    public boolean onHumanApproved(long userId, int threshold) {
        boolean released = accountCreditRepository.onHumanApproved(userId, threshold);
        stateCache.invalidate(userId);
        if (released) {
            log.info("新人观察期结束（人审通过达阈值 {}）: userId={}, level={}",
                    threshold, userId, getLevel(userId));
        }
        return released;
    }

    /** 违规确认（举报成立 / 人审驳回）：扣分降级，并置加严队列。 */
    public void onViolationConfirmed(long userId) {
        accountCreditRepository.deduct(userId);
        stateCache.invalidate(userId);
        log.info("账号信用扣分（违规确认）: userId={}, level={}", userId, getLevel(userId));
    }

    /** 申诉翻案：加分恢复等级。 */
    public void onAppealUpheld(long userId) {
        accountCreditRepository.restore(userId);
        stateCache.invalidate(userId);
        log.info("账号信用恢复（申诉翻案）: userId={}, level={}", userId, getLevel(userId));
    }
}
