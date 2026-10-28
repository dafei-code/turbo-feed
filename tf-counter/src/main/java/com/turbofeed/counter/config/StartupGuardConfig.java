package com.turbofeed.counter.config;

import com.turbofeed.shared.feature.FeatureFlagGuard;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 启动期特性开关校验接线（计数服务侧）。见 {@code com.turbofeed.gateway.config.StartupGuardConfig}。
 */
@Configuration
public class StartupGuardConfig {

    @Bean
    public static BeanFactoryPostProcessor featureFlagGuard(Environment env) {
        return (ConfigurableListableBeanFactory bf) ->
                new FeatureFlagGuard(env::getProperty, env.getActiveProfiles()).validate();
    }
}
