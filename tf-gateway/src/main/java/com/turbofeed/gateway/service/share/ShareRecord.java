package com.turbofeed.gateway.service.share;

import java.time.Instant;

/**
 * 短链映射领域模型（逻辑表 share_record 的一行）。
 *
 * <p><b>引用式分享</b>：snapshotJson 只存卡片元数据（封面 / 文案摘要 / 作者 id），
 * 不复制媒体主体；跳转时凭 mediaId + ownerId 回源校验当前状态（方案 §4.3 / §4.5）。</p>
 */
public record ShareRecord(
        String code,
        long id,
        String postId,
        String mediaId,
        long ownerId,
        long sharerId,
        String snapshotJson,
        int status,
        Instant createdAt
) {
    /** 有效映射状态。 */
    public static final int STATUS_ACTIVE = 1;
    /** 已失效（内容下架 / 运营作废）。 */
    public static final int STATUS_INVALID = 0;
}
