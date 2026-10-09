package com.turbofeed.gateway.service.health;

import java.time.Instant;

/**
 * 账号健康分（P0-b）。
 *
 * <p>与 {@code account_penalty}（硬封禁）解耦：健康分走「软处置」阶梯——扣分→推荐降权/限投稿/限变现，
 * 归零才硬封（{@link #score()} 归零时 {@code AccountHealthService#isBanned} 返回 true）。</p>
 */
public class AccountHealth {

    private final long userId;
    private final int score;
    private final int violationCount;
    private final Instant lastDeductAt;
    private final Instant lastRecoverAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    public AccountHealth(long userId, int score, int violationCount,
                         Instant lastDeductAt, Instant lastRecoverAt,
                         Instant createdAt, Instant updatedAt) {
        this.userId = userId;
        this.score = score;
        this.violationCount = violationCount;
        this.lastDeductAt = lastDeductAt;
        this.lastRecoverAt = lastRecoverAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long userId() {
        return userId;
    }

    public int score() {
        return score;
    }

    public int violationCount() {
        return violationCount;
    }

    public Instant lastDeductAt() {
        return lastDeductAt;
    }

    public Instant lastRecoverAt() {
        return lastRecoverAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
