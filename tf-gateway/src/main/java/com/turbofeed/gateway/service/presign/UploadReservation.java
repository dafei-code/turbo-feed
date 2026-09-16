package com.turbofeed.gateway.service.presign;

import com.turbofeed.gateway.service.ImageFormat;

import java.time.Instant;
import java.util.List;

/**
 * 预签名直传的「上传预约」：客户端拿到凭证后直传对象存储，服务端凭本预约在收尾阶段落库。
 *
 * <p><b>为什么必须有它</b>：预签名直传把一次上传拆成了「申请凭证 → 客户端直传 → 通知完成」
 * 三步。服务端在第一步就已经确定了 {@code postId} 与每个 {@code mediaId}（对象名），
 * 而这些信息在第二步之后客户端才真正把字节传上去。若不把这份「已签发但未落库」的状态存下来，
 * 完成阶段就只能信任客户端回传的 mediaId 列表——那是可被篡改的，等于把对象名交给客户端决定。
 * 存服务端预约后，完成阶段只认 {@code postId}，媒体清单以预约为准。</p>
 *
 * <p><b>生命周期</b>：{@code presign} 创建（{@link Status#RESERVED}）→ {@code complete}
 * 领取收尾权（{@link Status#COMMITTING}）→ 收尾成功后删除；失败则补偿清理后删除。
 * TTL 为凭证有效期 + 缓冲，过期未完成的预约连同其对象一并成为待清理的孤儿（见演进说明）。</p>
 *
 * <p><b>反序列化</b>：本 record 经 Jackson 与 Redis 互转，依赖类文件保留形参名
 * （{@code -parameters}，与既有的 {@code MediaItem} 幂等缓存同一前提）。</p>
 */
public record UploadReservation(
        /** 帖子 ID（一次上传批次），形如 {@code post/{userId}/{uuid}}。 */
        String postId,
        /** 归属用户（来自 JWT，完成阶段与本值比对防越权）。 */
        String userId,
        /** 本批待上传的槽位（seq 有序，决定前端轮播顺序）。 */
        List<Slot> slots,
        /** 原始描述/标题（已过敏感词校验）。 */
        String caption,
        /** 描述标记 JSON（@用户 / #话题 / [image:idx]）。 */
        String captionMark,
        /** 客户端幂等键（可空）。 */
        String requestId,
        /** 预约创建时间（即帖子创建时间）。 */
        Instant createdAt,
        /** 预约状态。 */
        Status status) {

    /**
     * 一个待上传槽位。
     *
     * @param seq          帖内序号（0 起）
     * @param mediaId      服务端生成的对象名（{@code media/{userId}/{uuid}.{ext}}），客户端无法指定
     * @param format       申请时按 contentType 声明的格式（完成阶段用文件头复检，不信任本值）
     * @param declaredSize 客户端声明的字节数（完成阶段用 statObject 复检，不信任本值）
     */
    public record Slot(int seq, String mediaId, ImageFormat format, long declaredSize) {
    }

    /** 预约状态。 */
    public enum Status {
        /** 已签发凭证，等待客户端直传并通知完成。 */
        RESERVED,
        /** 已完成校验并领取收尾权，正在异步落库 / 发事件。 */
        COMMITTING
    }
}
