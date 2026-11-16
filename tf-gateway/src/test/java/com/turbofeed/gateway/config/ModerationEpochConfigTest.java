package com.turbofeed.gateway.config;

import com.turbofeed.gateway.service.moderation.ModerationEpochBroker;
import com.turbofeed.gateway.service.moderation.SensitiveWordService;
import com.turbofeed.gateway.service.moderation.WhitelistService;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ModerationEpochConfig} 装配测试：锁定「开关拨到某个位置时，谁被装配、谁没被装配」。
 *
 * <p><b>为什么必须测装配</b>：{@code @ConditionalOnProperty} 按字面量查 Environment，
 * 属性名写错不会报错，只会「开关不动」（M1/3.2 在 tf-counter 上踩过）。B2 的默认关闭
 * 是零回归的承诺：<b>默认不建订阅容器、不启动订阅、不需要 Redis 连接工厂</b>，
 * 只靠读代码推演不够，必须断言出来。</p>
 */
class ModerationEpochConfigTest {

    private ApplicationContextRunner runner(boolean brokerEnabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ModerationEpochConfig.class)
                .withBean(SensitiveWordService.class, () -> mock(SensitiveWordService.class))
                .withBean(WhitelistService.class, () -> mock(WhitelistService.class))
                .withBean(ModerationEpochBroker.class,
                        () -> new ModerationEpochBroker(mock(StringRedisTemplate.class), brokerEnabled));
    }

    @Test
    void 默认关闭_不装配订阅容器_且无需Redis连接工厂() {
        runner(false).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ModerationEpochConfig.class);
            assertThat(ctx).doesNotHaveBean("moderationEpochContainer");
            assertThat(ctx).doesNotHaveBean("moderationEpochStarter");
            assertThat(ctx.getBean(ModerationEpochBroker.class).isEnabled()).isFalse();
            assertThat(ReflectionTestUtils.getField(
                    ctx.getBean(ModerationEpochConfig.class), "fallbackScheduler")).isNull();
        });
    }

    @Test
    void 显式关闭_同样不装配订阅容器() {
        runner(false)
                .withPropertyValues(FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED + "=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("moderationEpochContainer");
                    assertThat(ctx).doesNotHaveBean("moderationEpochStarter");
                });
    }

    @Test
    void 显式开启_才装配订阅容器与后台启动器() {
        runner(true)
                .withPropertyValues(FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED + "=true")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("moderationEpochContainer");
                    assertThat(ctx).hasBean("moderationEpochStarter");
                    assertThat(ReflectionTestUtils.getField(
                            ctx.getBean(ModerationEpochConfig.class), "fallbackScheduler")).isNotNull();
                });
    }

    @Test
    void broker构造器占位符_默认关闭且可被属性打开() {
        // 直接让 Spring 构造 broker（而不是像上面几个用例那样 new），验证 @Value 占位符真的解析
        new ApplicationContextRunner()
                .withUserConfiguration(ModerationEpochBroker.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ModerationEpochBroker.class).isEnabled()).isFalse();
                });

        new ApplicationContextRunner()
                .withUserConfiguration(ModerationEpochBroker.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues(FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED + "=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ModerationEpochBroker.class).isEnabled()).isTrue();
                });
    }

    @Test
    void 条件注解的属性名与FeatureFlagKeys常量逐字一致() throws Exception {
        Method container = ModerationEpochConfig.class.getMethod(
                "moderationEpochContainer", RedisConnectionFactory.class, ModerationEpochBroker.class);
        Method starter = ModerationEpochConfig.class.getMethod(
                "moderationEpochStarter", org.springframework.data.redis.listener.RedisMessageListenerContainer.class);

        for (Method m : new Method[]{container, starter}) {
            ConditionalOnProperty ann = m.getAnnotation(ConditionalOnProperty.class);
            assertThat(ann).as("%s 必须带门控注解", m.getName()).isNotNull();
            assertThat(ann.name()).containsExactly(FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED);
            assertThat(ann.havingValue()).isEqualTo("true");
            assertThat(ann.matchIfMissing()).isFalse();
        }
    }
}
