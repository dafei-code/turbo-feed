package com.turbofeed.gateway.service.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索索引冷补重建的回归防护。
 *
 * <p><b>为什么要锁这个</b>：重建作业是在「索引已经坏了」的时候才会跑——此刻没人有耐心
 * 逐条比对。最容易犯的错是：① 词典分数写成非 0（{@code ZRANGEBYLEX} 静默失效，联想全空）；
 * ② 倒排的 score 写成「重建时刻」而不是内容创建时间（老内容全挤到榜单最前面）；
 * ③ 重建出的键不带 TTL（内容删了索引还在，搜索持续捞出已删内容）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchRebuildServiceTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    StringRedisTemplate redis;

    @Mock
    ZSetOperations<String, String> zSetOps;

    @Mock
    SearchIndexWriter indexWriter;

    private SearchRebuildService service;

    @BeforeEach
    void setUp() {
        service = new SearchRebuildService(jdbcTemplate, redis, indexWriter);
    }

    @Test
    @DisplayName("话题词典重建：score 必须恒 0，空白词跳过")
    void rebuildTagLexiconWritesZeroScore() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("java", "  ", "后端"));
        when(redis.opsForZSet()).thenReturn(zSetOps);

        SearchRebuildService.RebuildReport report = service.rebuildTagLexicon(false, 100);

        assertEquals(2, report.scanned());
        assertEquals(2, report.written());
        assertEquals(1, report.skipped());
        // 分数非 0 会让 ZRANGEBYLEX 前缀扫描静默失效 → 联想接口直接全空
        verify(zSetOps).add(SearchIndexWriter.LEX_KEY, "tag:java", 0d);
        verify(zSetOps).add(SearchIndexWriter.LEX_KEY, "tag:后端", 0d);
    }

    @Test
    @DisplayName("话题词典重建 dryRun：只统计不写入")
    void rebuildTagLexiconDryRun() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("java"));

        SearchRebuildService.RebuildReport report = service.rebuildTagLexicon(true, 100);

        assertEquals(1, report.scanned());
        assertEquals(0, report.written());
        assertTrue(report.dryRun());
        verify(redis, never()).opsForZSet();
    }

    @Test
    @DisplayName("用户索引重建：复用在线写入口径（索引 + 词典一次写齐）")
    void rebuildUserIndexReusesWriter() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1001L);
        row.put("nickname", "E2E拼豆Fan");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        SearchRebuildService.RebuildReport report = service.rebuildUserIndex(false, 100);

        assertEquals(1, report.scanned());
        assertEquals(1, report.written());
        verify(indexWriter).indexUser(1001L, "E2E拼豆Fan");
    }

    @Test
    @DisplayName("标签倒排重建：score 用内容 created_at（不是重建时刻），无 TTL 时补 30 天")
    void rebuildTagIndexUsesCreatedAtAsScore() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("post_id", "post/1001/a");
        r1.put("tag", "java");
        r1.put("created_at", 1690000000000L);
        Map<String, Object> r2 = new HashMap<>();
        r2.put("post_id", "post/1001/a");
        r2.put("tag", "后端");
        r2.put("created_at", 1690000000000L);

        when(jdbcTemplate.queryForList(anyString(), eq((Object) Long.MAX_VALUE)))
                .thenReturn(List.of(r1, r2));
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.getExpire(anyString())).thenReturn(-1L);

        SearchRebuildService.RebuildReport report = service.rebuildTagIndex(false, 100);

        assertEquals(2, report.scanned());
        assertEquals(2, report.written());
        assertEquals(2, report.keys());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(zSetOps).add(eq("tf:tag:media:z:java"), captor.capture());
        ZSetOperations.TypedTuple<String> t = captor.getValue().iterator().next();
        assertEquals("post/1001/a", t.getValue());
        assertEquals(1690000000000d, t.getScore());
        // 重建出来的键不会有在线写路径续期 → 必须自带过期窗口
        verify(redis).expire(eq("tf:tag:media:z:java"), eq(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("标签倒排重建：已有 TTL 的键不被改寿命")
    void rebuildTagIndexKeepsExistingTtl() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("post_id", "post/1001/a");
        r1.put("tag", "java");
        r1.put("created_at", 1690000000000L);

        when(jdbcTemplate.queryForList(anyString(), eq((Object) Long.MAX_VALUE))).thenReturn(List.of(r1));
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.getExpire(anyString())).thenReturn(3600L);

        service.rebuildTagIndex(false, 100);

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("标签倒排重建 dryRun：不写 Redis、不改 TTL")
    void rebuildTagIndexDryRun() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("post_id", "post/1001/a");
        r1.put("tag", "java");
        r1.put("created_at", 1690000000000L);
        when(jdbcTemplate.queryForList(anyString(), eq((Object) Long.MAX_VALUE))).thenReturn(List.of(r1));

        SearchRebuildService.RebuildReport report = service.rebuildTagIndex(true, 100);

        assertEquals(1, report.scanned());
        assertEquals(0, report.written());
        verify(redis, never()).opsForZSet();
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(redis, never()).getExpire(anyString());
    }

    @Test
    @DisplayName("读库失败：返回带 note 的报告，不把异常抛给调用方")
    void rebuildReportsReadFailure() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("shardingsphere timeout"));

        SearchRebuildService.RebuildReport report = service.rebuildTagLexicon(false, 100);

        assertEquals(0, report.scanned());
        assertTrue(report.note() != null && report.note().contains("timeout"));
        verify(redis, never()).opsForZSet();
    }
}
