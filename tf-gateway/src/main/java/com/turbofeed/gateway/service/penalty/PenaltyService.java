package com.turbofeed.gateway.service.penalty;

import com.turbofeed.gateway.repository.AccountPenaltyRepository;
import com.turbofeed.gateway.repository.ViolationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 账号处罚服务（penalty 包骨架的编排层）。
 *
 * <p><b>领域边界</b>：本服务只管「硬执行」（封禁 / 警告，直接决定能不能写），
 * <b>不碰</b> {@code account_credit} 的「软声誉」（信用分 / 等级 / 流量池）——
 * 一次违规若同时要扣分，由调用方再调 {@code AccountCreditService#onViolationConfirmed}，
 * 两条通道互不耦合，避免「封禁解除逻辑」与「信用恢复逻辑」互相污染。</p>
 *
 * <p><b>集成缝（本次骨架未接线，属下一步）</b>：
 * <ul>
 *   <li>写路径（上传 / 评论 / 改资料）落库前应调 {@link #canWrite(long)}，false 即以
 *       {@code BizException(FORBIDDEN, "账号已被封禁")} 拒绝；</li>
 *   <li>违规确认路径（如 {@code MediaReviewService#onViolationConfirmed}）应调
 *       {@link #recordViolation} 落处罚。</li>
 * </ul>
 * ⚠️ 封禁是<b>高不可逆风险</b>操作，正式启用前须先补 P0-3 outbox
 * （当前引擎通知/时间线投递是 fail-open，误封后无补偿）。</p>
 *
 * <p><b>幂等与并发</b>：{@code recordViolation} 用 {@code ON DUPLICATE KEY UPDATE} 落计数，
 * 并发两次违规不会互相覆盖；{@code violation_record} 为 append-only，纠正靠新增反向记录。</p>
 */
@Service
public class PenaltyService {

    /**
     * 显式声明日志与构造注入（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}）。
     *
     * <p>⚠️ 原因同 {@code AccountPenaltyRepository}：本机构建环境下 Lombok 注解处理对新建文件
     * 不生效（本类表现为 {@code log} 找不到符号）。显式声明可保证任何构建方式下都能编译。</p>
     */
    private static final Logger log = LoggerFactory.getLogger(PenaltyService.class);

    private final ViolationRecordRepository violationRecordRepository;
    private final AccountPenaltyRepository accountPenaltyRepository;
    private final PenaltyEscalationPolicy escalationPolicy;

    public PenaltyService(ViolationRecordRepository violationRecordRepository,
                          AccountPenaltyRepository accountPenaltyRepository,
                          PenaltyEscalationPolicy escalationPolicy) {
        this.violationRecordRepository = violationRecordRepository;
        this.accountPenaltyRepository = accountPenaltyRepository;
        this.escalationPolicy = escalationPolicy;
    }

    /**
     * 记录一次违规并施加升级处置：写 {@code violation_record}（审计） + 更新 {@code account_penalty}（处罚态）。
     *
     * <p><b>顺序说明</b>：先按「累加后的计数」评估升级，再把真实动作写进审计记录——
     * 反过来会导致 {@code action_taken} 记成 NONE，审计失真。</p>
     *
     * @return 处置后的账号处罚态
     */
    public AccountPenalty recordViolation(long userId,
                                          ViolationCategory category,
                                          ViolationSeverity severity,
                                          ViolationSource source,
                                          Long relatedMediaId,
                                          String operator,
                                          String reason) {
        Instant now = Instant.now();

        // 1) 取当前态（无记录 → 默认 NORMAL / 累计 0）
        AccountPenalty cur = accountPenaltyRepository.get(userId)
                .orElse(new AccountPenalty(userId, AccountPenaltyStatus.NORMAL, null, null, null,
                        0, null, null, now));
        int newCount = cur.violationCount() + 1;
        Instant firstAt = cur.firstViolationAt() == null ? now : cur.firstViolationAt();

        // 2) 升级评估（先评估，审计才能带真实动作）
        PenaltyEscalationPolicy.Decision d = escalationPolicy.evaluate(category, severity, newCount, cur.status());

        // 3) 写审计（append-only）
        violationRecordRepository.insert(new ViolationRecord(null, userId, category, severity, source,
                d.action(), relatedMediaId, reason, operator, now));

        // 4) 落处罚态
        AccountPenalty next = new AccountPenalty(userId, d.newStatus(), d.banCategory(), d.banReason(),
                d.banUntil(), newCount, firstAt, now, now);
        accountPenaltyRepository.apply(userId, next);

        log.info("违规处置: userId={}, category={}, severity={}, action={}, status={}, banUntil={}, count={}",
                userId, category, severity, d.action(), d.newStatus(), d.banUntil(), newCount);
        return next;
    }

    /**
     * <b>解除封禁</b>（撤销 / 申诉翻案）——处罚域的「撤销通道」。
     *
     * <p><b>为什么必须有</b>：封禁是高不可逆操作。若只有 {@link #recordViolation} 而没有解除通道，
     * {@link AccountPenaltyStatus#BANNED_PERM} 一旦误封将<b>永久无法撤销</b>
     * （{@link AccountPenaltyStatus#BANNED_TEMP} 还能靠 {@code ban_until} 到期自愈，永久封不能）。
     * 因此本方法是封禁写路径上线前的<b>强制配套</b>。</p>
     *
     * <p><b>只清封禁字段，不重置累计违规数</b>：撤销一次处罚 ≠ 抹掉历史
     * （历史仍在 {@code violation_record}，append-only）。若重置计数，再犯要重新攒够阈值才能封，
     * 等于给惯犯发「免罚卡」；保留计数则解除后再违规会<b>立即</b>按累计次数触发封禁。</p>
     *
     * <p><b>审计</b>：写一条 {@link PenaltyAction#LIFT} 的反向记录（不删改任何历史违规记录）。</p>
     *
     * @return 是否实际解除了封禁（本来就没被封 → false，调用方无需处理）
     */
    public boolean liftPenalty(long userId, ViolationSource source, String operator, String reason) {
        Optional<AccountPenalty> opt = accountPenaltyRepository.get(userId);
        if (opt.isEmpty()) {
            return false;
        }
        AccountPenalty cur = opt.get();
        if (cur.status() != AccountPenaltyStatus.BANNED_TEMP
                && cur.status() != AccountPenaltyStatus.BANNED_PERM) {
            return false; // NORMAL / WARN 无需解除
        }

        // 反向审计留痕：类目沿用被解除的封禁类目（空则 OTHER 兜底）；严重度无意义，用 LOW 占位。
        violationRecordRepository.insert(new ViolationRecord(
                null,
                userId,
                cur.banCategory() == null ? ViolationCategory.OTHER : cur.banCategory(),
                ViolationSeverity.LOW,
                source,
                PenaltyAction.LIFT,
                null,
                reason,
                operator,
                Instant.now()));

        AccountPenalty lifted = new AccountPenalty(userId, AccountPenaltyStatus.NORMAL, null, null, null,
                cur.violationCount(), cur.firstViolationAt(), cur.lastViolationAt(), Instant.now());
        accountPenaltyRepository.apply(userId, lifted);

        log.info("封禁解除: userId={}, from={}, source={}, operator={}, reason={}",
                userId, cur.status(), source, operator, reason);
        return true;
    }

    /** 读取持久化的处罚态（无记录按 NORMAL）。⚠️ 本方法<b>不做过期推导</b>，见 {@link #canWrite}。 */
    public AccountPenaltyStatus getStatus(long userId) {
        return accountPenaltyRepository.get(userId)
                .map(AccountPenalty::status)
                .orElse(AccountPenaltyStatus.NORMAL);
    }

    /**
     * <b>集成缝</b>：账号当前是否可写。
     *
     * <p>临时封禁到期<b>不靠定时任务改状态</b>，而是在此读取时按 {@code ban_until} 推导——
     * 与 P0-5 加严窗口同手法（读取时推导优先），保证「到点即恢复」不依赖任务跑过。</p>
     */
    public boolean canWrite(long userId) {
        Optional<AccountPenalty> opt = accountPenaltyRepository.get(userId);
        if (opt.isEmpty()) {
            return true;
        }
        AccountPenalty a = opt.get();
        return switch (a.status()) {
            case BANNED_PERM -> false;
            // 临时封禁：ban_until 已过 → 视为已解除；未过 → 不可写。
            // ⚠️ ban_until 为空属数据异常（临时封必带到期时点），按 fail-closed 视为仍在封禁中，
            //    避免脏数据把被封账号静默放出来。
            case BANNED_TEMP -> !(a.banUntil() == null || a.banUntil().isAfter(Instant.now()));
            default -> true; // NORMAL / WARN
        };
    }

    /** 当前是否处于封禁态（临时封禁已过期的按已解除算，语义等价 {@code !canWrite}）。 */
    public boolean isBanned(long userId) {
        return !canWrite(userId);
    }
}
