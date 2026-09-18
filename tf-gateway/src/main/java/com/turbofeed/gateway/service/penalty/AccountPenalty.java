package com.turbofeed.gateway.service.penalty;

import java.time.Instant;

/**
 * 账号处罚态（对应 account_penalty 表，按 user_id 分片）。
 *
 * @param userId           账号 ID（分片键）
 * @param status           处罚态（NORMAL/WARN/BANNED_TEMP/BANNED_PERM）
 * @param banCategory      触发封禁的类目（仅封禁时填）
 * @param banReason        封禁理由
 * @param banUntil         临时封禁到期时点（NULL=永久）；{@link PenaltyService#canWrite} 读取时若已过期按可写处理
 * @param violationCount   累计违规次数（升级判断输入；骨架版用总数，后续可改 JSON 类目计数）
 * @param firstViolationAt 首次违规时点
 * @param lastViolationAt  最近一次违规时点
 * @param updatedAt        最近更新时间
 */
public record AccountPenalty(
        long userId,
        AccountPenaltyStatus status,
        ViolationCategory banCategory,
        String banReason,
        Instant banUntil,
        int violationCount,
        Instant firstViolationAt,
        Instant lastViolationAt,
        Instant updatedAt) {
}
