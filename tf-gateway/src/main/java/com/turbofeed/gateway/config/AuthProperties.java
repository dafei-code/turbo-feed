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

    /**
     * 演示登录账号：手机号 -&gt; 账号信息（密码 + UID）。
     *
     * <p>对标抖音：登录账号即手机号（凭证层），仅用于登录入口定位 UID；签发给客户端的
     * 令牌 sub 携带的是系统内部 {@code uid}（身份层 / 分片键），手机号不进入令牌、
     * 不参与分片计算。生产改为数据库存储 + 哈希（BCrypt/Argon2）校验，并接入
     * user_phone_router 路由表做 phone -&gt; uid 映射。</p>
     */
    private Map<String, DemoUser> demoUsers = new LinkedHashMap<>();

    public Map<String, DemoUser> getDemoUsers() {
        return demoUsers;
    }

    public void setDemoUsers(Map<String, DemoUser> demoUsers) {
        this.demoUsers = demoUsers;
    }

    /** 演示账号条目：password 用于校验；uid 作为 JWT sub（对标抖音的 UID 身份层）。 */
    public static class DemoUser {
        private String password = "";
        private Long uid;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Long getUid() {
            return uid;
        }

        public void setUid(Long uid) {
            this.uid = uid;
        }
    }
}
