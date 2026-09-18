package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（turbofeed.jwt.*）。
 *
 * <p><b>签名密钥无默认值（刻意）</b>：本类字段与 {@code application.yml} 两处<b>都不得</b>出现
 * 字面量密钥，两道防线缺一不可——只外部化 yml 不够，占位符缺失时会回落到这里的字段初始值。</p>
 *
 * <p><b>为什么禁止兜底</b>：HS256 是对称算法，持有密钥即可签发<b>任意 userId 与任意 role</b> 的令牌。
 * 一旦存在一个写死的兜底值，生产漏配就退化为「全站使用公开密钥」，等价于鉴权整体失效。
 * 参考 {@link SnowflakeConfig#requireInstanceId} 的取舍：这类「漏配会导致静默灾难」的配置，
 * 正确做法是<b>缺失即启动失败</b>，把错误挡在部署阶段。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.jwt")
public class JwtProperties {

    /** HS256 要求密钥长度不小于 256 bit，故按字符数取下限（UTF-8 下 ≥ 32 字符）。 */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * HS256 签名密钥。由 {@code turbofeed.jwt.secret} 注入，<b>无默认值</b>；
     * 生产应来自密钥管理，本地开发经环境变量 {@code TURBOFEED_JWT_SECRET} 注入。
     */
    private String secret;

    /** 令牌有效期（秒），默认 24 小时。 */
    private long expireSeconds = 86400;

    /**
     * 校验签名密钥并返回它：<b>缺失或强度不足一律抛 {@link IllegalStateException}</b>，绝不做静默兜底。
     *
     * <p><b>为什么这里也要查长度</b>：只查「非空」无法挡住 {@code 123456} 这类弱密钥——
     * HS256 的强度直接取决于密钥熵量，短密钥可被暴力枚举还原，拿到后即可伪造任意身份。</p>
     *
     * <p>调用点固定在 {@link com.turbofeed.gateway.security.JwtUtil} 的构造器：
     * JwtUtil 是单例 bean，其构造失败发生在 <b>context refresh 阶段</b>，效果即「应用拒绝启动」，
     * 而不是等到第一次签发/验签才抛 {@link NullPointerException}。</p>
     *
     * @return 校验通过的密钥
     * @throws IllegalStateException 缺失、为空白或长度不足 {@value #MIN_SECRET_LENGTH}
     */
    public String requireSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 JWT 签名密钥 turbofeed.jwt.secret（无默认值，必须显式配置）。"
                            + "请通过环境变量 TURBOFEED_JWT_SECRET 注入一个长度不少于 " + MIN_SECRET_LENGTH
                            + " 字符的高熵随机串；例如 openssl rand -base64 32。"
                            + "刻意不做兜底默认值：HS256 是对称算法，持有密钥即可签发任意 userId 与 role 的令牌，"
                            + "存在写死的默认值会让「生产漏配」退化为全站使用公开密钥。");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT 签名密钥强度不足：当前长度 " + secret.length() + "，应不少于 " + MIN_SECRET_LENGTH
                            + " 个字符。HS256 的强度取决于密钥熵量，短密钥可被暴力枚举；"
                            + "请用高熵随机串（openssl rand -base64 32）替换 turbofeed.jwt.secret。");
        }
        return secret;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    public void setExpireSeconds(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }
}
