package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（turbofeed.jwt.*）。
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.jwt")
public class JwtProperties {

    /** HS256 签名密钥。演示默认值仅供本地跑通，生产必须替换为密钥管理注入的高熵值。 */
    private String secret = "turbofeed-demo-secret-change-me";

    /** 令牌有效期（秒），默认 24 小时。 */
    private long expireSeconds = 86400;

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
