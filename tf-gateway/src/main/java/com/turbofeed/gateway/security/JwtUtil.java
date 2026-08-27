package com.turbofeed.gateway.security;

import com.turbofeed.gateway.config.JwtProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * 轻量 JWT 签发 / 校验（HS256，零三方依赖，演示实现）。
 *
 * <p>工程约束：
 * <ul>
 *   <li>失败路径统一抛 {@link BizException}(UNAUTHORIZED)，由全局异常处理器翻译为 Result，
 *       调用方不捕获散弹式受检异常；</li>
 *   <li>签名比对使用 {@link MessageDigest#isEqual} 恒定时间比较，防时序侧信道；</li>
 *   <li>{@link Clock} 可注入，单测可控时间；生产建议替换为 jjwt / java-jwt，
 *       并接入密钥管理与 refresh token。</li>
 * </ul></p>
 */
@Component
public class JwtUtil {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final long MILLIS_PER_SECOND = 1000L;

    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public JwtUtil(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 测试专用构造器：注入可控时钟。 */
    JwtUtil(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 签发令牌，载荷含 sub(用户ID) / iat(签发时间) / exp(过期时间)，秒级时间戳。 */
    public String generateToken(String userId) {
        long nowSec = clock.millis() / MILLIS_PER_SECOND;
        String payload = "{\"sub\":\"" + escape(userId) + "\",\"iat\":" + nowSec
                + ",\"exp\":" + (nowSec + properties.getExpireSeconds()) + "}";
        String signingInput = base64Url(HEADER_JSON) + "." + base64Url(payload);
        return signingInput + "." + hmac(signingInput);
    }

    /** 校验签名与有效期，成功返回用户 ID；任何失败抛 BizException(UNAUTHORIZED, 具体原因)。 */
    public String parseUserId(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("令牌为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized("令牌格式非法");
        }
        if (!constantTimeEquals(hmac(parts[0] + "." + parts[1]), parts[2])) {
            throw unauthorized("令牌签名校验失败");
        }
        String payload = decodePayload(parts[1]);
        String sub = jsonField(payload, "sub");
        if (sub == null || sub.isEmpty()) {
            throw unauthorized("令牌缺少用户标识");
        }
        String exp = jsonField(payload, "exp");
        if (exp == null || clock.millis() / MILLIS_PER_SECOND >= parseExp(exp)) {
            throw unauthorized("令牌已过期");
        }
        return sub;
    }

    // ---------- 内部工具 ----------

    private static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    private static long parseExp(String exp) {
        try {
            return Long.parseLong(exp);
        } catch (NumberFormatException e) {
            throw unauthorized("令牌载荷非法");
        }
    }

    private static String decodePayload(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized("令牌载荷非法");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 计算。HmacSHA256 为 JDK 内置算法，缺失即运行环境不可用。 */
    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC 算法初始化失败", e);
        }
    }

    /**
     * 极简 JSON 字段值提取（仅本类固定格式载荷使用）。
     * 非通用 JSON 解析，生产替换为 JSON 库（Jackson）后本方法随之删除。
     */
    private static String jsonField(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }
        int start = json.indexOf(':', keyIndex) + 1;
        int comma = json.indexOf(',', start);
        int brace = json.indexOf('}', start);
        int end = (comma < 0) ? brace : Math.min(comma, brace);
        if (start <= 0 || end < start) {
            return null;
        }
        return json.substring(start, end).trim().replace("\"", "");
    }
}
