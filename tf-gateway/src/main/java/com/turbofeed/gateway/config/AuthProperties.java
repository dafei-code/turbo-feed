package com.turbofeed.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录配置（turbofeed.auth.*）。
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.auth")
public class AuthProperties {

    /** 演示账号：用户名 -&gt; 密码。生产改为数据库存储 + 哈希（BCrypt/Argon2）校验。 */
    private Map<String, String> demoUsers = new LinkedHashMap<>(Map.of("admin", "123456"));

    public Map<String, String> getDemoUsers() {
        return demoUsers;
    }

    public void setDemoUsers(Map<String, String> demoUsers) {
        this.demoUsers = demoUsers;
    }
}
