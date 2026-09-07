package com.turbofeed.gateway.service.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 机审占位实现：直接放行（APPROVED）。
 *
 * <p>当前未接入真实内容安全服务，以自动通过演示 PENDING -&gt; APPROVED 闭环。
 * <b>生产务必替换为真实机审实现</b>（返回 REJECTED 触发驳回），否则 UGC 内容裸奔涉政涉黄。</p>
 */
@Slf4j
@Component
public class AutoPassModeration implements ContentModeration {

    @Override
    public MediaStatus moderate(String mediaId, long userId, String url) {
        log.info("机审占位：自动通过（接入内容安全 API 后替换）mediaId={}, userId={}", mediaId, userId);
        return MediaStatus.APPROVED;
    }
}
