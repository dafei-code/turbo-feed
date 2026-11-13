package com.turbofeed.gateway.service.moderation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义层 Bean 装配。
 *
 * <p>仅当 {@code turbofeed.content-security.semantic.enabled=true} 时才创建
 * {@link ContentScorer}；否则 {@code ObjectProvider} 取不到，决策层自动跳过语义层。
 * 与 MeterRegistry 的 ObjectProvider 注入同一思路：可选增强，不硬依赖。</p>
 */
@Configuration
public class ModerationSemanticConfig {

    @Bean
    @ConditionalOnProperty(prefix = "turbofeed.content-security.semantic", name = "enabled", havingValue = "true")
    public ContentScorer semanticScorer(ContentSecurityProperties properties) {
        return new HttpSemanticScorer(properties.getSemantic());
    }
}
