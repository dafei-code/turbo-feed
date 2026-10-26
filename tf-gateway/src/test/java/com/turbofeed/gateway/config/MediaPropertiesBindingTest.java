package com.turbofeed.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MediaProperties} 归组后的配置绑定契约（M1 / 3.5）。
 *
 * <p>把扁平字段收进 {@code upload.*} / {@code processing.*} 子域时，最大的风险不是编译，
 * 而是<b>线上已有的 yml / ConfigMap / 运维脚本全在用旧键</b>。本测试锁死三条：</p>
 * <ol>
 *   <li>新键能绑定（归组真的生效，不是只改了 Java 侧）；</li>
 *   <li><b>旧键仍然生效</b>——这是兼容的核心；</li>
 *   <li>新旧两条路径读到的是<b>同一个值</b>：旧 getter 委托到新组，不存在两份状态漂移。</li>
 * </ol>
 *
 * <p>第 3 条尤其重要：如果哪天有人把旧字段重新加回成一个独立字段（而不是委托），
 * 前两条可能都还绿，但「配旧键、读新组」会读到默认值——那正是本项目反复出现过的
 * 静默失效。委托关系必须被测试按住。</p>
 */
class MediaPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Test
    void 新键绑定到upload子域() {
        runner.withPropertyValues(
                        "turbofeed.media.upload.max-file-size=7MB",
                        "turbofeed.media.upload.max-batch-count=3",
                        "turbofeed.media.upload.idempotent-ttl-seconds=11")
                .run(ctx -> {
                    MediaProperties p = ctx.getBean(MediaProperties.class);
                    assertThat(p.getUpload().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(7));
                    assertThat(p.getUpload().getMaxBatchCount()).isEqualTo(3);
                    assertThat(p.getUpload().getIdempotentTtlSeconds()).isEqualTo(11);
                });
    }

    @Test
    void 旧键仍然生效且委托到新组() {
        runner.withPropertyValues(
                        "turbofeed.media.max-file-size=9MB",
                        "turbofeed.media.max-batch-count=4")
                .run(ctx -> {
                    MediaProperties p = ctx.getBean(MediaProperties.class);
                    // 旧键读得到
                    assertThat(p.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(9));
                    assertThat(p.getMaxBatchCount()).isEqualTo(4);
                    // 且已经落到新组里（同一份状态，不是两份）
                    assertThat(p.getUpload().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(9));
                    assertThat(p.getUpload().getMaxBatchCount()).isEqualTo(4);
                });
    }

    @Test
    void 旧键写入新组后两者始终一致() {
        runner.withPropertyValues("turbofeed.media.idempotent-ttl-seconds=42")
                .run(ctx -> {
                    MediaProperties p = ctx.getBean(MediaProperties.class);
                    assertThat(p.getIdempotentTtlSeconds()).isEqualTo(42);
                    assertThat(p.getUpload().getIdempotentTtlSeconds()).isEqualTo(42);
                });
    }

    @Test
    void processing新旧键一致() {
        runner.withPropertyValues(
                        "turbofeed.media.processing.enabled=true",
                        "turbofeed.media.processing.thumbnail-max-dimension=1024")
                .run(ctx -> {
                    MediaProperties p = ctx.getBean(MediaProperties.class);
                    assertThat(p.getProcessing().isEnabled()).isTrue();
                    assertThat(p.isProcessingEnabled()).isTrue();
                    assertThat(p.getProcessing().getThumbnailMaxDimension()).isEqualTo(1024L);
                    assertThat(p.getThumbnailMaxDimension()).isEqualTo(1024L);
                });
    }

    @Test
    void 未配置时用默认值() {
        runner.run(ctx -> {
            MediaProperties p = ctx.getBean(MediaProperties.class);
            assertThat(p.getUpload().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(5));
            assertThat(p.getUpload().getMaxBatchCount()).isEqualTo(9);
            assertThat(p.getUpload().getIdempotentTtlSeconds()).isEqualTo(5);
            assertThat(p.getProcessing().isEnabled()).isFalse();
            assertThat(p.getProcessing().getThumbnailMaxDimension()).isEqualTo(2048L);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MediaProperties.class)
    static class BindingConfig {
    }
}
