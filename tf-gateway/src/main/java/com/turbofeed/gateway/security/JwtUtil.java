package com.turbofeed.gateway.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 轻量 JWT 签发 / 校验（HS256，零三方依赖，演示实现）。
 *
 * <p>工程约束：
 * <ul>
 *   <li>失败路径统一抛 {@link BizException}(UNAUTHORIZED)，由全局异常处理器翻译为 Result，
 *       调用方不捕获散弹式受检异常；</li>
 *   <li>签名比对使用 {@link MessageDigest#isEqual} 恒定时间比较，防时序侧信道；</li>
 *   <li>载荷序列化/反序列化统一走 Jackson {@link ObjectMapper}（线程安全，作静态单例），
 *       取代早期手写的 {@code indexOf} 字段提取——后者对嵌套/转义 JSON 脆弱，
 *       且无法稳健处理 {@code jti} 等新声明；</li>
 *   <li>{@link Clock} 可注入，单测可控时间；生产建议替换为 jjwt / java-jwt，
 *       并接入密钥管理与 refresh token。</li>
 * </ul></p>
 *
 * <p><b>载荷格式（确定性，向后兼容）</b>：{@code {"sub","role","iat","exp"}}，
 * 签发时追加 {@code "jti"}。键顺序固定为 sub→role→iat→exp→jti，
 * 与改造前手拼字符串的字节序一致，故旧令牌（无 jti）仍可被本实现验签通过。</p>
 */
@Component
public class JwtUtil {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final String ACCESS_TYPE = "access";
    private static final String REFRESH_TYPE = "refresh";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public JwtUtil(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 测试专用构造器：注入可控时钟。
     *
     * <p><b>构造即校验密钥</b>：此处调用 {@link JwtProperties#requireSecret()}。
     * JwtUtil 是单例 bean，其构造器抛异常发生在 <b>context refresh 阶段</b>，
     * 效果就是「应用拒绝启动」——而不是等到第一次签发/验签才在 {@link #hmac} 里
     * 抛 {@link NullPointerException}。这是刻意的：<b>漏配密钥属于部署错误，
     * 应该在部署阶段暴露，不该拖到运行期某一个请求上。</b></p>
     *
     * <p>不放在 {@code @PostConstruct} 里是因为 {@code @ConfigurationProperties} 的
     * 属性绑定与 JSR-250 初始化回调的执行次序依赖 BeanPostProcessor 顺序，
     * 构造器里校验则次序确定（属性必然已绑定完成）。</p>
     */
    JwtUtil(JwtProperties properties, Clock clock) {
        this.properties = properties;
        // 缺失/弱密钥在这里引爆：越过这一步即代表密钥可用
        properties.requireSecret();
        this.clock = clock;
    }

    /** 签发令牌（默认 USER 角色），载荷含 sub / role(默认USER) / iat / exp / jti，秒级时间戳。 */
    public String generateToken(String userId) {
        return generateToken(userId, "USER");
    }

    /**
     * 签发令牌（携带角色），载荷含 sub(用户ID) / role(角色编码) / iat / exp / jti，秒级时间戳。
     *
     * <p>role 为 null 时降级为 USER（最小权限，不允许越权签发）。
     * jti 为每次签发的随机 UUID，是「单令牌吊销」（登出 / 风控踢人）的寻址键，
     * 见 {@link TokenBlacklist}。</p>
     */
    public String generateToken(String userId, String role) {
        long nowSec = clock.millis() / MILLIS_PER_SECOND;
        String roleClaim = (role == null || role.isBlank()) ? "USER" : role;
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("role", roleClaim);
        payload.put("iat", nowSec);
        payload.put("exp", nowSec + properties.getExpireSeconds());
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("type", ACCESS_TYPE);
        String signingInput = base64Url(HEADER_JSON) + "." + base64Url(writeJson(payload));
        return signingInput + "." + hmac(signingInput);
    }

    /**
     * 签发刷新令牌（refresh token）。
     *
     * <p>与访问令牌的区别：<b>不含 role</b>（刷新只证明「身份仍有效」，不携带授权；
     * 每次刷新都用最新 user.role 重签访问令牌，使角色变更即时生效），载荷带
     * {@code type=refresh} 与独立 {@code jti}，有效期取 {@code refreshExpireSeconds}（远长于访问令牌）。
     * 刷新令牌服务端有状态存储（见 {@link com.turbofeed.gateway.security.RefreshTokenStore}），
     * 可主动吊销 / 轮换，弥补纯无状态 JWT 的短板。</p>
     */
    public String generateRefreshToken(String userId) {
        long nowSec = clock.millis() / MILLIS_PER_SECOND;
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("iat", nowSec);
        payload.put("exp", nowSec + properties.getRefreshExpireSeconds());
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("type", REFRESH_TYPE);
        String signingInput = base64Url(HEADER_JSON) + "." + base64Url(writeJson(payload));
        return signingInput + "." + hmac(signingInput);
    }

    /** 校验签名与有效期，成功返回用户 ID；任何失败抛 BizException(UNAUTHORIZED, 具体原因)。 */
    public String parseUserId(String token) {
        return parseClaims(token).userId();
    }

    /**
     * 校验签名与有效期，成功返回完整声明（userId + role + jti）；任何失败抛 BizException(UNAUTHORIZED)。
     *
     * <p>失败路径与 {@link #parseUserId} 完全一致（共享底层验签/过期校验），仅返回值更丰富，
     * 供 {@link JwtAuthenticationFilter} 一次性解析身份与角色与 jti，避免重复验签。</p>
     *
     * <p><b>注意</b>：本方法只做「签名 + 有效期」校验，不做黑名单（吊销）校验——
     * 吊销是 Redis 依赖的 IO 操作，交由 {@link TokenBlacklist} 在过滤器层统一执行，
     * 保持本类的「纯加密 / 无 IO」边界（便于无 Redis 环境下单测）。</p>
     */
    public JwtClaims parseClaims(String token) {
        Map<String, Object> payload = verifyAndDecode(token);
        String sub = str(payload, "sub");
        if (sub == null || sub.isEmpty()) {
            throw unauthorized("令牌缺少用户标识");
        }
        long expSec = toLong(payload.get("exp"), "exp");
        if (clock.millis() / MILLIS_PER_SECOND >= expSec) {
            throw unauthorized("令牌已过期");
        }
        String role = str(payload, "role");
        String jti = str(payload, "jti");
        String type = str(payload, "type");
        return new JwtClaims(sub, role == null ? "USER" : role, jti, type);
    }

    /**
     * 解析刷新令牌：验签 + 校验 type=refresh + 有效期 + jti。
     *
     * <p>仅供 {@code /auth/refresh} 与吊销使用。若令牌类型不是 refresh（如有人拿访问令牌来刷新）、
     * 已过期、或无可吊销 jti，统一抛 UNAUTHORIZED。</p>
     */
    public JwtClaims parseRefresh(String token) {
        Map<String, Object> payload = verifyAndDecode(token);
        String sub = str(payload, "sub");
        if (sub == null || sub.isEmpty()) {
            throw unauthorized("令牌缺少用户标识");
        }
        String type = str(payload, "type");
        if (!REFRESH_TYPE.equals(type)) {
            throw unauthorized("非刷新令牌");
        }
        long expSec = toLong(payload.get("exp"), "exp");
        if (clock.millis() / MILLIS_PER_SECOND >= expSec) {
            throw unauthorized("刷新令牌已过期");
        }
        String jti = str(payload, "jti");
        if (jti == null || jti.isEmpty()) {
            throw unauthorized("刷新令牌无可吊销标识(jti)");
        }
        return new JwtClaims(sub, str(payload, "role"), jti, type);
    }

    /**
     * 仅供吊销使用：仅校验签名，<b>不校验有效期</b>，返回 jti 与过期 epoch 秒。
     *
     * <p>临近过期或已被本方法之外的路径判定为「仍需吊销」的令牌，可借此取出 jti 与剩余有效期，
     * 由 {@link TokenBlacklist} 写入黑名单（TTL = 剩余有效期）。
     * 旧格式令牌（无 jti）无法单点吊销，统一抛 UNAUTHORIZED。</p>
     */
    public RevocableToken parseForRevoke(String token) {
        Map<String, Object> payload = verifyAndDecode(token);
        String jti = str(payload, "jti");
        if (jti == null || jti.isEmpty()) {
            throw unauthorized("令牌无可吊销标识(jti)");
        }
        long expSec = toLong(payload.get("exp"), "exp");
        return new RevocableToken(jti, expSec);
    }

    /** 可吊销令牌元信息（仅签名校验通过后出现）。 */
    public record RevocableToken(String jti, long expireAtEpochSec) {}

    /** JWT 解析结果（不可变值对象）。 */
    public static final class JwtClaims {
        private final String userId;
        private final String role;
        private final String jti;
        private final String type;

        JwtClaims(String userId, String role, String jti, String type) {
            this.userId = userId;
            this.role = role;
            this.jti = jti;
            this.type = type;
        }

        public String userId() {
            return userId;
        }

        public String role() {
            return role;
        }

        /** 令牌唯一标识；旧格式令牌可能为 null（无法单点吊销）。 */
        public String jti() {
            return jti;
        }

        /** 令牌类型：access / refresh；旧格式令牌为 null。 */
        public String type() {
            return type;
        }
    }

    // ---------- 内部工具 ----------

    /** 验签 + 解码载荷为 Map；格式/签名非法统一抛 UNAUTHORIZED。不做有效期判断（供 parseClaims / parseForRevoke 共用）。 */
    private Map<String, Object> verifyAndDecode(String token) {
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
        String payloadJson = decodePayload(parts[1]);
        try {
            return MAPPER.readValue(payloadJson, MAP_TYPE);
        } catch (Exception e) {
            throw unauthorized("令牌载荷非法");
        }
    }

    private static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    private static String writeJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 载荷序列化失败", e);
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static long toLong(Object value, String key) {
        if (value == null) {
            throw unauthorized("令牌载荷缺少 " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
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
}
