package com.turbofeed.gateway.service.review;

/**
 * 内容审核端口（机审能力抽象）。
 *
 * <p>默认实现 {@link AutoPassModeration} 直接放行（演示/占位）；接入真实内容安全
 * （阿里云内容安全 / 腾讯天御 / 自建模型）时新增实现并替换激活即可，
 * 审核主流程（{@code MediaReviewService}）零改动。</p>
 *
 * @param mediaId 内容标识
 * @param userId  归属用户（分片键，预留做用户级风控策略）
 * @param url     媒体可访问地址（机审可能需下载原图识别）
 * @return 机审裁定状态（APPROVED / REJECTED）
 */
public interface ContentModeration {

    MediaStatus moderate(String mediaId, long userId, String url);
}
