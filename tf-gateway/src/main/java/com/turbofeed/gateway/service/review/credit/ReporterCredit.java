package com.turbofeed.gateway.service.review.credit;

import java.time.Instant;

/**
 * 举报人信用（P1 #131）。
 *
 * <p>与账号信用（account_credit，内容审核分流）解耦：本对象只描述「举报行为信用」——
 * 举报成立率越高信用越高，被驳回越多信用越低；低信用举报人的举报<b>不计入</b>复审升级（防恶意刷台）。</p>
 */
public class ReporterCredit {

    private final long userId;
    private final int totalReports;
    private final int upheld;
    private final int rejected;
    private final int credibility;
    private final Instant lastReportAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ReporterCredit(long userId, int totalReports, int upheld, int rejected,
                          int credibility, Instant lastReportAt, Instant createdAt, Instant updatedAt) {
        this.userId = userId;
        this.totalReports = totalReports;
        this.upheld = upheld;
        this.rejected = rejected;
        this.credibility = credibility;
        this.lastReportAt = lastReportAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long userId() {
        return userId;
    }

    public int totalReports() {
        return totalReports;
    }

    public int upheld() {
        return upheld;
    }

    public int rejected() {
        return rejected;
    }

    public int credibility() {
        return credibility;
    }

    public Instant lastReportAt() {
        return lastReportAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
