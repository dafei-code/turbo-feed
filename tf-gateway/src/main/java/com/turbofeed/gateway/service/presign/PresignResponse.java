package com.turbofeed.gateway.service.presign;

import java.util.List;

/**
 * 预签名上传凭证响应（{@code POST /api/media/presign} 返回体）。
 *
 * <p>客户端据此逐张直传对象存储：对每个 {@link PresignItem#uploadUrl()} 发起
 * {@code PUT}（请求体为原始字节，并<b>必须</b>携带与声明一致的 {@code Content-Type} 头——
 * 该头已被纳入签名，不一致会被对象存储以签名校验失败拒绝）。全部传完后调用
 * {@code POST /api/media/complete}。</p>
 *
 * <p><b>安全边界</b>：URL 只对「指定对象名 + PUT 方法 + 有效期内」生效，
 * 客户端既不能改写对象名（对象名由服务端生成），也不能越权读写桶内其他对象。</p>
 *
 * @param postId        帖子 ID（一次上传批次），完成阶段回传本值
 * @param items         逐张上传项（按 seq 升序，即前端轮播顺序）
 * @param expirySeconds 凭证有效期（秒），超时未传完需重新申请
 */
public record PresignResponse(String postId, List<PresignItem> items, int expirySeconds) {

    /**
     * 单张图的上传项。
     *
     * @param seq       帖内序号（0 起）
     * @param mediaId   服务端生成的对象名（内容唯一标识，完成阶段与查询均以此为主键）
     * @param uploadUrl 预签名 PUT 地址（客户端直传用，服务端不收字节流）
     */
    public record PresignItem(int seq, String mediaId, String uploadUrl) {
    }
}
