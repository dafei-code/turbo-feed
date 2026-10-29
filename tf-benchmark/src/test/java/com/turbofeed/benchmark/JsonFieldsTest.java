package com.turbofeed.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonFields} 单测：压测的「成功」判定完全依赖从这里读出的 {@code code}，
 * 这里判错就会产出假 QPS，因此边界要钉死。
 */
class JsonFieldsTest {

    @Test
    void readsTopLevelCode() {
        String json = "{\"code\":0,\"message\":\"成功\",\"data\":null,\"timestamp\":1}";
        assertThat(JsonFields.intField(json, "code", -1)).isZero();
    }

    @Test
    void readsNonZeroBusinessCode() {
        String json = "{\"code\":40101,\"message\":\"未登录\",\"data\":null}";
        assertThat(JsonFields.intField(json, "code", -1)).isEqualTo(40101);
    }

    @Test
    void missingFieldReturnsDefault() {
        assertThat(JsonFields.intField("{\"message\":\"x\"}", "code", -1)).isEqualTo(-1);
        assertThat(JsonFields.intField("", "code", -1)).isEqualTo(-1);
        assertThat(JsonFields.intField(null, "code", -7)).isEqualTo(-7);
    }

    @Test
    void whitespaceAroundColonTolerated() {
        assertThat(JsonFields.intField("{ \"code\" :  0 }", "code", -1)).isZero();
    }

    /**
     * 关键防误判：评论内容里塞了 {@code "code":0} 字样时，不能把失败响应当成功。
     * 成员位置判定（前一个非空白字符须为 <code>{</code> 或 <code>,</code>）负责挡住它。
     */
    @Test
    void ignoresFieldNameInsideStringValue() {
        // 注意顺序：字符串值里的 \"code\":0 出现在真正字段之前，才能验证「跳过非成员位置」。
        String json = "{\"message\":\"你写的是 \\\"code\\\":0 吗\",\"code\":40101,\"data\":null}";
        assertThat(JsonFields.intField(json, "code", -1)).isEqualTo(40101);
    }

    @Test
    void readsStringField() {
        String json = "{\"code\":0,\"data\":{\"accessToken\":\"eyJhbGciOiJIUzI1NiJ9.abc.def\",\"refreshToken\":\"r\"}}";
        assertThat(JsonFields.stringField(json, "accessToken")).isEqualTo("eyJhbGciOiJIUzI1NiJ9.abc.def");
        assertThat(JsonFields.stringField(json, "refreshToken")).isEqualTo("r");
        assertThat(JsonFields.stringField(json, "missing")).isNull();
    }

    @Test
    void decodesEscapes() {
        String json = "{\"content\":\"a\\\"b\\\\c\\nd\\u4e2d\"}";
        assertThat(JsonFields.stringField(json, "content")).isEqualTo("a\"b\\c\nd中");
    }

    @Test
    void nullValueTreatedAsMissing() {
        assertThat(JsonFields.stringField("{\"accessToken\":null}", "accessToken")).isNull();
    }

    @Test
    void unclosedStringReturnsNull() {
        assertThat(JsonFields.stringField("{\"accessToken\":\"abc", "accessToken")).isNull();
    }

    @Test
    void negativeIntSupported() {
        assertThat(JsonFields.intField("{\"code\":-1}", "code", 99)).isEqualTo(-1);
    }
}
