package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核员配置（turbofeed.reviewer.*）。
 *
 * <p>demo 阶段用「手机号白名单」派生审核员角色：登录时手机号命中 {@code phones}
 * 即签发 {@link com.turbofeed.gateway.security.Role#REVIEWER} 角色，可访问内容审核
 * 工作台（{@code /api/admin/media/*} 的审核类接口），但无系统管理权限。生产应改为
 * user 表 role 列 + RBAC（与 {@code turbofeed.admin.phones} 合并为统一角色来源）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.reviewer")
public class ReviewerProperties {

    /** 审核员手机号白名单：登录命中即颁发 REVIEWER 角色。 */
    private List<String> phones = new ArrayList<>();

    public List<String> getPhones() {
        return phones;
    }

    public void setPhones(List<String> phones) {
        this.phones = phones;
    }
}
