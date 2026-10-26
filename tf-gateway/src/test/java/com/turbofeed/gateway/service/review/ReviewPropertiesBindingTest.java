package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewProperties} 独立成类后的配置绑定契约（M1 / 3.5）。
 *
 * <p>审核配置原是 {@code MediaProperties} 的嵌套类（636 行、34 个字段）。拆出来时
 * <b>刻意保持 prefix 不变</b>（仍是 {@code turbofeed.media.review}），所以线上 yml /
 * ConfigMap / 运维脚本零改动。但「配置键不变」这件事本身需要被测试按住——一旦有人顺手
 * 把 prefix 改成 {@code turbofeed.review}，编译照过、单测照绿，只有线上配置静默失效
 * （读回默认值：机审直接放行 / 阈值回到硬编码），这正是本项目反复出现过的失效模式。</p>
 *
 * <p>本测试锁死四条：</p>
 * <ol>
 *   <li><b>键不变</b>：{@code turbofeed.media.review.*} 仍绑得上（顶层 + 嵌套组）；</li>
 *   <li><b>嵌套与枚举 Key 的 Map 仍能绑</b>：{@code moderation-policy.by-source.RULE} 这类
 *       枚举键 Map 是全类最容易在搬移中坏掉的结构（搬错了包、枚举换了位置都不报错）；</li>
 *   <li><b>与 {@code MediaProperties} 并存</b>：两个 {@code @ConfigurationProperties} Bean 的
 *       prefix 是父子关系（{@code turbofeed.media} vs {@code turbofeed.media.review}），
 *       父级不再有 {@code review} 字段后，多余的键必须被静默忽略而不是启动失败；</li>
 *   <li><b>默认值</b>：不配置的键读回代码内默认值（尤其 {@code auto-pass=false}——
 *       这是「UGC 不裸奔」的闸门，默认值反了就是线上事故）。</li>
 * </ol>
 */
class ReviewPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Test
    void 顶层键仍绑定在turbofeed_media_review前缀下() {
        runner.withPropertyValues(
                        "turbofeed.media.review.auto-pass=true",
                        "turbofeed.media.review.risk-enabled=false",
                        "turbofeed.media.review.risk-low-threshold=0.45",
                        "turbofeed.media.review.report-re-review-threshold=7",
                        "turbofeed.media.review.pool-heat-threshold-l2=555")
                .run(ctx -> {
                    ReviewProperties r = ctx.getBean(ReviewProperties.class);
                    assertThat(r.isAutoPass()).isTrue();
                    assertThat(r.isRiskEnabled()).isFalse();
                    assertThat(r.getRiskLowThreshold()).isEqualTo(0.45);
                    assertThat(r.getReportReReviewThreshold()).isEqualTo(7);
                    assertThat(r.getPoolHeatThresholdL2()).isEqualTo(555);
                });
    }

    @Test
    void 嵌套组cloud与disposition矩阵仍绑定() {
        runner.withPropertyValues(
                        "turbofeed.media.review.cloud.endpoint=https://green.example.com",
                        "turbofeed.media.review.cloud.timeout-ms=1234",
                        "turbofeed.media.review.disposition-matrix[USER_REPORT.HIGH]=TAKEN_DOWN")
                .run(ctx -> {
                    ReviewProperties r = ctx.getBean(ReviewProperties.class);
                    assertThat(r.getCloud().getEndpoint()).isEqualTo("https://green.example.com");
                    assertThat(r.getCloud().getTimeoutMs()).isEqualTo(1234);
                    assertThat(r.getDispositionMatrix()).containsEntry("USER_REPORT.HIGH", "TAKEN_DOWN");
                });
    }

    @Test
    void 严格度矩阵的枚举键Map仍绑定() {
        runner.withPropertyValues(
                        "turbofeed.media.review.moderation-policy.default-strictness=STRICT",
                        "turbofeed.media.review.moderation-policy.by-source.RULE=RELAXED",
                        "turbofeed.media.review.moderation-policy.by-scenario.PRIVATE=RELAXED",
                        "turbofeed.media.review.moderation-policy.profiles.STRICT.pass-threshold=0.97")
                .run(ctx -> {
                    ReviewProperties.ModerationPolicyConfig cfg =
                            ctx.getBean(ReviewProperties.class).getModerationPolicy();
                    assertThat(cfg.getDefaultStrictness()).isEqualTo(ModerationStrictness.STRICT);
                    assertThat(cfg.getBySource()).containsEntry(ModerationMode.RULE, ModerationStrictness.RELAXED);
                    assertThat(cfg.getByScenario())
                            .containsEntry(ModerationScenario.PRIVATE, ModerationStrictness.RELAXED);
                    assertThat(cfg.getProfiles().get(ModerationStrictness.STRICT).getPassThreshold())
                            .isEqualTo(0.97);
                });
    }

    @Test
    void 与MediaProperties并存时父子前缀互不干扰() {
        runner.withPropertyValues(
                        "turbofeed.media.max-file-size=8MB",
                        "turbofeed.media.review.auto-pass=true",
                        "turbofeed.media.review.risk-med-threshold=0.8")
                .run(ctx -> {
                    // 父级绑自己的键，且不会因多出 review.* 子树而启动失败
                    MediaProperties p = ctx.getBean(MediaProperties.class);
                    assertThat(p.getUpload().getMaxFileSize().toMegabytes()).isEqualTo(8);
                    // 子级绑自己的键，两者互不覆盖
                    ReviewProperties r = ctx.getBean(ReviewProperties.class);
                    assertThat(r.isAutoPass()).isTrue();
                    assertThat(r.getRiskMedThreshold()).isEqualTo(0.8);
                });
    }

    @Test
    void 未配置时读回默认值且审核闸默认关闭自动放行() {
        runner.run(ctx -> {
            ReviewProperties r = ctx.getBean(ReviewProperties.class);
            // 安全默认值：不配就是「真审核闸」，绝不裸奔
            assertThat(r.isAutoPass()).isFalse();
            assertThat(r.getModerationMode()).isEqualTo(ModerationMode.RULE);
            assertThat(r.getConfidenceThreshold()).isEqualTo(0.9);
            assertThat(r.getReportReReviewThreshold()).isEqualTo(3);
            assertThat(r.getHeatReReviewThreshold()).isEqualTo(1000);
            assertThat(r.getPoolHeatThresholdL1()).isEqualTo(1000);
            assertThat(r.getPoolHeatThresholdL3()).isEqualTo(200);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ReviewProperties.class, MediaProperties.class})
    static class BindingConfig {
    }
}
