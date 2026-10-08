package com.turbofeed.gateway.service.review;

import java.time.Instant;

/**
 * 复审任务（举报累计触发的人工复审队列条目）。
 *
 * <p>抖音式「举报累计 → 人工复核」的承接结构：当同一内容的待处理举报数达到阈值，
 * {@code MediaReviewService#report} 自动建一条 PENDING 复审任务，归 REVIEWER 二次研判。</p>
 *
 * <p>单表（ds_0，{@code review_task}），不按 media_id 分片——它是管理员视角的聚合队列，
 * 按 status 扫描全貌比按 media 点查更重要（仿 outbox_event 的 SINGLE 取舍）。</p>
 *
 * <p><b>第 2 档（changelog 0066，抖音式「热度阈值复审 / 越火审得越严」）</b>：在
 * {@link #TYPE_REPORT_ACCUMULATED}（举报累计）之外新增 {@link #TYPE_HEAT_ACCUMULATED}
 * （正向互动累计达阈值）。两者共用同一条队列与 {@code decideReviewTask} 二次研判流程，
 * REVIEWER 在队列里按 {@code taskType} 区分来源。</p>
 */
public record ReviewTask(
        long id,
        String mediaId,
        long authorId,
        String taskType,
        int triggerCount,
        int status,
        Instant createdAt,
        Instant resolvedAt,
        String resolver) {

    /** 任务状态：0=待复审 / 1=复审无违规(维持发布) / 2=复审确认违规(已下架)。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RESOLVED = 1;
    public static final int STATUS_TAKEDOWN = 2;

    /** 触发类型：举报累计达到阈值。 */
    public static final String TYPE_REPORT_ACCUMULATED = "REPORT_ACCUMULATED";

    /** 触发类型：正向互动（点赞+评论+转发）累计达到热度阈值（抖音式「越火审得越严」）。 */
    public static final String TYPE_HEAT_ACCUMULATED = "HEAT_ACCUMULATED";
}
