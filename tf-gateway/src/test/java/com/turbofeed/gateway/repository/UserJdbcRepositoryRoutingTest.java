package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.domain.User;
import com.turbofeed.gateway.support.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1 靶心③：用户分片路由（{@link UserJdbcRepository}）。
 *
 * <p><b>为什么用真 MySQL（Testcontainers）而非 H2</b>：ShardingSphere 5.5.3 在 H2 上的 SQL 解析 /
 * 分片行为与 MySQL 不一致，H2 通过 ≠ 生产通过。本集成测起真实 MySQL，验证「insert→findById
 * 单分片命中」「countByPhone 走路由表」「resolveUserIdByPhone 广播兜底 + 惰性回填」。</p>
 *
 * <p><b>范围说明</b>：本测验证的是「我们写的路由逻辑」（phone→uid 路由表 + 广播兜底），
 * 物理分片键（id）的 ShardingSphere SQL 改写属框架层、由 ShardingSphere 自身测试覆盖；
 * 此处用单张 {@code user} 表即可验证仓库 SQL 与路由闭环的正确性。</p>
 *
 * <p><b>无 Docker 跳过</b>：{@link RequiresDocker} 守卫——本地未起 Docker 时本测被 disabled，
 * 不阻塞 {@code mvn test}；CI（有 Docker）跑全量。</p>
 */
@RequiresDocker
@Testcontainers
@JdbcTest
@Import({UserJdbcRepository.class, UserPhoneRouterRepository.class})
class UserJdbcRepositoryRoutingTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("turbo_feed")
            .withInitScript("schema-user.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired UserJdbcRepository userRepository;

    @Test
    void insert_then_findById_hitsSingleShard() {
        userRepository.insert(new User(123L, "13800000000", "hash", "nick", 1));

        Optional<UserJdbcRepository.UserIdHash> found = userRepository.findById(123L);
        assertTrue(found.isPresent());
        assertEquals("USER", found.get().role(), "role 为 null 时落库默认 USER");
    }

    @Test
    void countByPhone_usesRouterAfterBind() {
        userRepository.insert(new User(123L, "13800000000", "hash", "nick", 1));

        // 路由表未绑定 → 广播兜底命中并回填
        assertEquals(1L, userRepository.countByPhone("13800000000"));
        // 回填后再次解析走路由（单表 PK 点查，与分片数无关）
        Optional<Long> uid = userRepository.resolveUserIdByPhone("13800000000");
        assertTrue(uid.isPresent());
        assertEquals(123L, uid.get());
    }

    @Test
    void resolveUserIdByPhone_broadcastFallback_thenBackfill() {
        userRepository.insert(new User(456L, "13900000000", "hash2", "nick2", 1));

        Optional<Long> uid = userRepository.resolveUserIdByPhone("13900000000");
        assertTrue(uid.isPresent());
        assertEquals(456L, uid.get());
        // 回填生效：第二次解析仍走路由、结果一致（幂等）
        assertEquals(456L, userRepository.resolveUserIdByPhone("13900000000").orElse(-1L));
    }
}
