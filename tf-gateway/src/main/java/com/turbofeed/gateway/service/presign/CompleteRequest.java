package com.turbofeed.gateway.service.presign;

/**
 * 直传完成通知（{@code POST /api/media/complete} 请求体）。
 *
 * <p>客户端把所有图片直传到对象存储后调用本接口。只回传 {@code postId}（由服务端在
 * 申请凭证时生成），<b>不接受客户端声明媒体清单</b>——清单以服务端预约为准，
 * 避免客户端伪造 mediaId 落库他人/他人路径的对象。</p>
 *
 * @param postId 帖子 ID（取自 {@link PresignResponse#postId()}）
 */
public record CompleteRequest(String postId) {
}
