package com.turbofeed.gateway.trace;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.turbofeed.shared.trace.TraceIds;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4：prod profile 用的结构化 JSON 编码器可用性验证。
 *
 * <p>只验 encoder 本身，不启动 Spring 上下文：{@code logback-spring.xml} 的
 * {@code <springProfile>} 切换由 Boot 的 LogbackLoggingSystem 负责，起容器验证成本过高；
 * 这里锁死真正可能悄悄坏掉的三件事——依赖类能否加载、输出是否为单行合法 JSON、
 * MDC 维度与 service 字段是否真的进了 JSON。配置文件的 XML 合法性由构建期校验。</p>
 */
class StructuredLoggingTest {

    @Test
    @DisplayName("LogstashEncoder：单行 JSON，含 service 与 MDC 维度")
    void encoder_emitsJsonWithMdc() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setCustomFields("{\"service\":\"tf-gateway\",\"application\":\"turbo-feed\"}");
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();

        ch.qos.logback.classic.Logger logger = context.getLogger("structured-logging-probe");
        boolean originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        MDC.put(TraceIds.MDC_KEY, "trace-probe-123");
        MDC.put(TraceIds.MDC_INSTANCE_KEY, "instance-probe-1");
        try {
            logger.info("结构化日志探针");
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
            MDC.remove(TraceIds.MDC_INSTANCE_KEY);
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
            encoder.stop();
        }

        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.startsWith("{"), "应输出单行 JSON，实际: " + json);
        assertTrue(json.contains("\"service\":\"tf-gateway\""), json);
        assertTrue(json.contains("trace-probe-123") && json.contains("instance-probe-1"),
                "MDC 维度必须出现在 JSON 中，否则集中日志仍无法按链路检索: " + json);
        assertNull(MDC.get(TraceIds.MDC_KEY), "探针不得残留 MDC 污染同线程其它用例");
    }

    @Test
    @DisplayName("logback-spring.xml：prod 分支存在且引用 JSON encoder")
    void prodProfileUsesJsonEncoder() throws Exception {
        try (var stream = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertTrue(stream != null, "logback-spring.xml 必须打进 classpath");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("<springProfile name=\"prod\">"), "缺少 prod 分支");
            assertTrue(xml.contains("LogstashEncoder"), "prod 必须走 JSON encoder");
            assertTrue(xml.contains("<includeContext>false</includeContext>"),
                    "Nacos 把系统属性写进 LoggerContext，必须关掉 context provider 避免 JSON 噪声");
            for (String key : new String[]{"traceId", "instanceId", "cycleId", "userId", "postId"}) {
                assertTrue(xml.contains("<includeMdcKeyName>" + key + "</includeMdcKeyName>"),
                        "MDC 白名单缺少 " + key + "：新增业务维度必须同步登记，否则 prod 日志查不到");
            }
            assertTrue(xml.contains("<springProfile name=\"!prod\">"),
                    "必须保留非 prod 文本分支，否则本地日志可读性会被破坏");
            assertEquals(1, xml.split("<springProfile name=\"prod\">", -1).length - 1,
                    "prod 分支只能有一份：重复定义会让前一个 encoder 被静默覆盖");
        }
    }
}
