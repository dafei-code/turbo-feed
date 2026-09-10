package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员配置（turbofeed.admin.*）。
 *
 * <p>demo 阶段用「手机号白名单」派生管理员角色：登录时若手机号命中
 * {@code phones}，则签发 ADMIN 角色令牌；否则为普通 USER。生产应替换为
 * user 表 role 列 + RBAC（见 MediaProperties 注释与 user_schema.sql）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.admin")
public class AdminProperties {

    /** 管理员手机号白名单：登录命中即颁发 ADMIN 角色。 */
    private List<String> phones = new ArrayList<>();

    public List<String> getPhones() {
        return phones;
    }

    public void setPhones(List<String> phones) {
        this.phones = phones;
    }
}
