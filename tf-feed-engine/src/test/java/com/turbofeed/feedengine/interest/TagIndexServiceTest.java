package com.turbofeed.feedengine.interest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签倒排索引的<b>双写一致性</b>回归防护（S1 搜索的写入侧地基）。
 *
 * <p><b>为什么要锁这个</b>：搜索读的是有序倒排 {@code tf:tag:media:z:{tag}}，而兴趣召回读的是
 * 无序集合 {@code tf:tag:media:{tag}}。两者必须<b>同源同寿命</b>：只写 SET 不写 ZSET → 搜索永远空；
 * 只删 SET 不删 ZSET → 已下架内容仍能被搜到。这类「半边更新」不会报错，只能靠测试拦住。</p>
 */
@ExtendWith(MockitoExtension.class)
class TagIndexServiceTest {

    private static final String KEY = "post/1001/2f7c9a";
    private static final List<String> TAGS = List.of("java", "后端");

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    SetOperations<String, String> setOps;

    @Mock
    ZSetOperations<String, String> zSetOps;

    private TagIndexService service;

    @BeforeEach
    void setUp() {
        service = new TagIndexService(redisTemplate);
    }

    @Test
    @DisplayName("index：每个标签同时写入无序集合与有序倒排（搜索读路径不能缺）")
    void indexWritesBothIndexes() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        service.index(KEY, TAGS);

        for (String tag : TAGS) {
            verify(setOps).add("tf:tag:media:" + tag, KEY);
            verify(zSetOps).add(eq("tf:tag:media:z:" + tag), eq(KEY), anyDouble());
            // 两个键都要设 TTL，否则索引比内容活得久（脏数据）
            verify(redisTemplate).expire(eq("tf:tag:media:" + tag), any(Duration.class));
            verify(redisTemplate).expire(eq("tf:tag:media:z:" + tag), any(Duration.class));
        }
        verify(setOps).add(eq("tf:media:tags:" + KEY), any(String[].class));
    }

    @Test
    @DisplayName("remove：下架时两个索引都要摘除，否则已下架内容仍可被搜到")
    void removeDropsBothIndexes() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(setOps.members("tf:media:tags:" + KEY)).thenReturn(Set.copyOf(TAGS));

        service.remove(KEY);

        for (String tag : TAGS) {
            verify(setOps).remove("tf:tag:media:" + tag, KEY);
            verify(zSetOps).remove("tf:tag:media:z:" + tag, KEY);
        }
        verify(redisTemplate).delete("tf:media:tags:" + KEY);
    }

    @Test
    @DisplayName("index：空标签 / 空键直接忽略，不做任何 Redis 写（防脏键 tf:tag:media:z:）")
    void indexIgnoresEmptyInput() {
        service.index(KEY, List.of());
        service.index("  ", TAGS);

        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("index：Redis 异常 fail-open，不向调用方抛出（绝不阻断入流主链路）")
    void indexIsFailOpen() {
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("redis down"));

        service.index(KEY, TAGS);

        // 未抛异常即通过；同时确认没有把半截索引写进 ZSET
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("remove：空键直接忽略")
    void removeIgnoresBlankKey() {
        service.remove(null);
        service.remove("  ");

        verify(redisTemplate, never()).opsForSet();
    }
}
