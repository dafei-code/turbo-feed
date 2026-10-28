package com.turbofeed.gateway.config;

import com.turbofeed.shared.feature.FeatureFlagGuard;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 启动期特性开关校验接线（网关侧）。
 *
 * <p>以 {@code static @Bean BeanFactoryPostProcessor} 在上下文 refresh 早期、单例实例化之前调用
 * {@link FeatureFlagGuard#validate()}，抢在 RocketMQ / Redisson 等 starter 的"缺 Bean 静默失败"之前报错。</p>
 */
@Configuration
public class StartupGuardConfig {

    @Bean
    public static BeanFactoryPostProcessor featureFlagGuard(Environment env) {
        return (ConfigurableListableBeanFactory bf) ->
                new FeatureFlagGuard(env::getProperty, env.getActiveProfiles()).validate();
    }
}
