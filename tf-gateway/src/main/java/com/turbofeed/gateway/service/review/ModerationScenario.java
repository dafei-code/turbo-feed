package com.turbofeed.gateway.service.review;

/**
 * 内容分发场景（feature-match M3，双严格度矩阵维度之一）。
 *
 * <p>PUBLIC_FEED=进公域推荐流(从严，漏放代价高)；PRIVATE=仅本人/私域可见(宽松)。
 * 当前 turbo-feed 的 UGC 上传均走 {@code PUBLIC_FEED}；私信/头像等私域场景预留扩展位。</p>
 */
public enum ModerationScenario {
    PUBLIC_FEED,
    PRIVATE
}
