package com.turbofeed.gateway.service.penalty;

import java.time.Instant;

/**
 * 违规记录（append-only 审计，对应 violation_record 表）。升级决策的「事实源」。
 *
 * @param id             记录 ID（分片主键，由 ShardingSphere 雪花算法填充，应用侧不传）
 * @param userId         账号 ID（分片键 user_id，与 user/media/account_credit 同片）
 * @param category       违规类目
 * @param severity       严重度
 * @param source         发现来源
 * @param actionTaken    本次采取的动作
 * @param relatedMediaId 关联内容 ID（内容违规时填，账号/行为违规可空）
 * @param reason         处置理由（人审/运营填写）
 * @param operator       操作人（机审为 SYSTEM，人审为审核员 ID）
 * @param createdAt      记录时间
 */
public record ViolationRecord(
        Long id,
        long userId,
        ViolationCategory category,
        ViolationSeverity severity,
        ViolationSource source,
        PenaltyAction actionTaken,
        Long relatedMediaId,
        String reason,
        String operator,
        Instant createdAt) {
}
