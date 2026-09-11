package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户持久层（JDBC，经 ShardingSphere DataSource 路由到 user 分片）。
 *
 * <p><b>分片路由约束</b>：user 表分片键为 {@code id}。INSERT 显式带 id（应用层雪花生成），
 * ShardingSphere 按 id 精准命中单分片。注册/登录按 phone 查询无分片键，ShardingSphere 会
 * 全分片广播并合并结果——注册去重靠 {@link #countByPhone} 兜底，登录取合并后首行
 * （手机号全局唯一，正常仅一行）。跨分片唯一性的生产级方案见 user_schema.sql 头的
 * user_phone_router 路由表，本 demo 阶段不引入。</p>
 */
@Repository
@RequiredArgsConstructor
public class UserJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 注册去重：手机号是否已被占用（无分片键，广播合并 COUNT）。 */
    public long countByPhone(String phone) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE phone = ?", Long.class, phone);
        return count == null ? 0L : count;
    }

    /** 落库一条用户记录。id 为分片键，ShardingSphere 按 id 精准路由。 */
    public void insert(User user) {
        // role 为 null（如注册路径未显式指定）时按最小权限 USER 落库，避免空角色。
        String role = (user.getRole() == null || user.getRole().isBlank()) ? "USER" : user.getRole();
        jdbcTemplate.update(
                "INSERT INTO user (id, phone, password_hash, nickname, status, role) VALUES (?, ?, ?, ?, ?, ?)",
                user.getId(), user.getPhone(), user.getPasswordHash(), user.getNickname(), user.getStatus(), role);
    }

    /** 登录定位：按手机号取 id + 密码哈希 + 状态 + 角色（无分片键，广播合并后取首行）。 */
    public Optional<UserIdHash> findByPhone(String phone) {
        List<UserIdHash> list = jdbcTemplate.query(
                "SELECT id, password_hash, status, role FROM user WHERE phone = ?",
                (rs, rn) -> new UserIdHash(rs.getLong("id"), rs.getString("password_hash"), rs.getInt("status"), rs.getString("role")),
                phone);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 登录用轻量投影：仅取登录必需的字段，避免把整行用户对象暴露给 Service。 */
    public record UserIdHash(Long id, String passwordHash, int status, String role) {
    }
}
