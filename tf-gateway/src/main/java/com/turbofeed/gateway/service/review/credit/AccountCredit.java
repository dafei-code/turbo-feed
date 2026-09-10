package com.turbofeed.gateway.service.review.credit;

import java.time.Instant;

/**
 * 账号信用画像（单条记录，对应 account_credit 表）。
 *
 * @param userId       账号 ID（分片键，与 user/media 一致，便于单元化）
 * @param creditScore  信用分（0-100，默认 100；违规扣分、申诉翻案加回）
 * @param level        信用等级（{@link CreditLevel} 编码）
 * @param strictQueue  是否处于加严队列（近 30 天有下架 → true，所有内容按 L0 口径先审后放）
 * @param updatedAt    最近更新时间
 */
public record AccountCredit(long userId, int creditScore, CreditLevel level, boolean strictQueue, Instant updatedAt) {
}
