package com.turbofeed.gateway.service.review.credit;

import java.time.Instant;

/**
 * 账号信用画像（单条记录，对应 account_credit 表）。
 *
 * @param userId                账号 ID（分片键，与 user/media 一致，便于单元化）
 * @param creditScore           信用分（0-100，默认 100；违规扣分、申诉翻案加回）
 * @param level                 信用等级（{@link CreditLevel} 编码）
 * @param strictQueue           违规加严（近 30 天有下架 → true，按 L0 口径先审后放；当前无自动解除路径）
 * @param newUserWatch          新人观察期（新注册账号默认 true，按 L0 口径先审后放；人审通过 N 帖后自动解除）
 * @param newUserApprovedCount  观察期内累计人审通过帖数
 * @param updatedAt             最近更新时间
 */
public record AccountCredit(long userId, int creditScore, CreditLevel level, boolean strictQueue,
                            boolean newUserWatch, int newUserApprovedCount, Instant updatedAt) {
}
