package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索回查契约的集成测（S1 的数据库底座，P0-1 工程债）。
 *
 * <p><b>为什么必须有这篇</b>：搜索的「单分片回查」建立在两个前提上——
 * ① {@code listPostImages(user_id, post_id)} 按 {@code seq} 升序返回<b>整帖</b>；
 * ② 历史单图数据（{@code post_id=''}）能被 {@code findMedia(media_id, user_id)} 命中。
 * 这两条一旦被改坏，搜索会「静默少图 / 静默查不到」，而 Mockito 单测发现不了——
 * 问题出在 SQL 与列语义上。</p>
 *
 * <h3>为什么不用 Testcontainers（与 {@code UserJdbcRepositoryRoutingTest} 的差异）</h3>
 * <p>本项目原先的两个集成测都走 Testcontainers 起 MySQL。2026-10-20 实测：<b>本机 Docker
 * 的 CLI 可用（{@code docker info} 正常），但 HTTP API 被桌面代理拦成 400</b>，Testcontainers
 * 的 Java 客户端连不上，直接抛 {@code Could not find a valid Docker environment}——
 * 集成测在本地无法运行，等于没有。故本篇改为<b>连接外部 MySQL</b>：由环境变量提供连接串，
 * 未设置即整类跳过（不阻塞 {@code mvn test}），CI 里用 service container 提供同一个 MySQL 即可。</p>
 *
 * <h3>如何运行</h3>
 * <pre>
 * mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS tf_it_media DEFAULT CHARSET utf8mb4"
 * TF_IT_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/tf_it_media?useSSL=false&amp;allowPublicKeyRetrieval=true&amp;serverTimezone=UTC' \
 * TF_IT_MYSQL_USER=root TF_IT_MYSQL_PASSWORD=*** \
 * mvn -pl tf-gateway test -Dtest=MediaJdbcRepositorySearchTest
 * </pre>
 * 表结构由 {@code schema-media.sql} 在测试类启动前建好（{@code CREATE TABLE IF NOT EXISTS}，可重复执行）。
 */
@JdbcTest
@Import(MediaJdbcRepository.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TF_IT_MYSQL_URL", matches = ".+")
@Sql(scripts = "classpath:schema-media.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class MediaJdbcRepositorySearchTest {

    private static final long USER_ID = 1001L;
    private static final String POST_ID = "post/1001/2f7c9a";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TF_IT_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> env("TF_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> env("TF_IT_MYSQL_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null ? fallback : v;
    }

    @Autowired
    MediaJdbcRepository repository;

    @Test
    @DisplayName("整帖回查：返回全部图片且严格按 seq 升序（搜索封面取第一张）")
    void listPostImages_returnsAllOrderedBySeq() {
        // 刻意乱序插入：验证排序来自 SQL 而非插入顺序
        insert(POST_ID, "media/1001/c.jpg", 2, MediaStatus.APPROVED);
        insert(POST_ID, "media/1001/a.jpg", 0, MediaStatus.APPROVED);
        insert(POST_ID, "media/1001/b.jpg", 1, MediaStatus.APPROVED);

        List<MediaItem> rows = repository.listPostImages(USER_ID, POST_ID);

        assertEquals(3, rows.size(), "整帖 3 张图都要回查到");
        assertEquals(List.of("media/1001/a.jpg", "media/1001/b.jpg", "media/1001/c.jpg"),
                rows.stream().map(MediaItem::mediaId).toList(),
                "必须按 seq 升序——搜索结果的封面 = 第一张");
        assertEquals(0, rows.get(0).seq());
        assertEquals(MediaStatus.APPROVED, rows.get(0).status());
    }

    @Test
    @DisplayName("整帖回查：别的用户的同 post_id 不会串进来（回查必须带 user_id）")
    void listPostImages_isScopedByUser() {
        insert(POST_ID, "media/1001/a.jpg", 0, MediaStatus.APPROVED);
        repository.insert(POST_ID, "media/2002/x.jpg", 2002L, "http://cdn/x.jpg",
                MediaStatus.APPROVED, "别人的帖", null, 0, Instant.now(), null);

        List<MediaItem> rows = repository.listPostImages(USER_ID, POST_ID);

        assertEquals(1, rows.size());
        assertEquals("media/1001/a.jpg", rows.get(0).mediaId());
    }

    @Test
    @DisplayName("历史单图帖（post_id 为空）：findMedia 仍能精确命中")
    void findMedia_hitsLegacyRow() {
        repository.insert("", "media/1001/legacy.jpg", USER_ID, "http://cdn/legacy.jpg",
                MediaStatus.APPROVED, "老数据", null, 0, Instant.now(), null);

        MediaItem item = repository.findMedia("media/1001/legacy.jpg", USER_ID);

        assertNotNull(item);
        assertEquals("media/1001/legacy.jpg", item.mediaId());
        assertEquals(MediaStatus.APPROVED, item.status());
        assertTrue(item.images().contains("http://cdn/legacy.jpg"), "images 至少含自身");
    }

    @Test
    @DisplayName("未命中返回 null / 空列表（搜索要据此跳过该条，不能抛异常）")
    void missingRows_areQuiet() {
        assertNull(repository.findMedia("media/1001/nope.jpg", USER_ID));
        assertTrue(repository.listPostImages(USER_ID, "post/1001/nope").isEmpty());
        assertTrue(repository.listPostImages(USER_ID, "").isEmpty(), "空 postId 走短路，不查库");
    }

    @Test
    @DisplayName("非 APPROVED 行如实返回状态（整帖可见性由调用方判，仓库不做过滤）")
    void statusIsReturnedAsIs() {
        insert(POST_ID, "media/1001/p.jpg", 0, MediaStatus.PENDING);

        List<MediaItem> rows = repository.listPostImages(USER_ID, POST_ID);

        assertEquals(1, rows.size());
        assertEquals(MediaStatus.PENDING, rows.get(0).status());
    }

    private void insert(String postId, String mediaId, int seq, MediaStatus status) {
        repository.insert(postId, mediaId, USER_ID, "http://cdn/" + mediaId, status,
                "caption " + mediaId, null, seq, Instant.now(), null);
    }
}
