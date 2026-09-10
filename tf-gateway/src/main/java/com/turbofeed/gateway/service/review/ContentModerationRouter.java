package com.turbofeed.gateway.service.review;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.turbofeed.gateway.config.MediaProperties;

/**
 * 机审路由选择器（可插拔的核心）。
 *
 * <p><b>职责</b>：收集所有 {@link ContentModeration} 实现，按配置
 * {@code turbofeed.media.review.moderation-mode} 选中匹配 {@link ModerationMode} 的那一个作为
 * 实际执行者；找不到匹配实现时回退到 PASS（占位桩），保证审核主流程永远有可用实现、绝不空指针。</p>
 *
 * <p><b>为什么本类不实现 {@link ContentModeration}</b>：避免被 {@code List<ContentModeration>}
 * 注入时把自己也算进去、也避免与具体实现抢「按类型注入」的唯一的 Bean 位。
 * {@code MediaReviewService} 注入的是本路由类（类型唯一），由本类转发到选中的实现。</p>
 *
 * <p><b>扩展性</b>：新增机审能力（如云内容安全 API）→ 新增一个 {@code ContentModeration} 实现，
 * {@code @Override mode()} 返回新枚举值，并在枚举与配置补该值即可，本类与审核主流程零改动。</p>
 */
@Slf4j
@Component
public class ContentModerationRouter {

    private final ContentModeration delegate;

    public ContentModerationRouter(MediaProperties mediaProperties, List<ContentModeration> candidates) {
        ModerationMode mode = mediaProperties.getReview().getModerationMode();
        this.delegate = candidates.stream()
                .filter(c -> c.mode() == mode)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("未找到机审实现匹配 moderation-mode={}，回退到 PASS 占位桩（恒 APPROVED，待人审）", mode);
                    return candidates.stream()
                            .filter(c -> c.mode() == ModerationMode.PASS)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("无任何 ContentModeration 实现可用，审核链路断裂"));
                });
        log.info("机审实现已激活：moderation-mode={}, impl={}", mode, delegate.getClass().getSimpleName());
    }

    /** 转发机审请求到当前激活的实现。 */
    public MediaStatus moderate(String mediaId, long userId, String url) {
        return delegate.moderate(mediaId, userId, url);
    }
}
