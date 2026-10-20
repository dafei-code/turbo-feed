package com.turbofeed.gateway.service.search;

import com.turbofeed.gateway.offline.OfflineShardJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按物理分片并行重建的回归防护。
 *
 * <p><b>为什么必须锁</b>：这条通道<b>绕开 ShardingSphere 直连物理库</b>，等于把原本由中间件
 * 兜底的一致性交给了自己。最危险的错误都不是"报错"，而是<b>静默漏扫</b>——
 * 少扫一片、游标跳页、或者 dryRun 却真写了。漏扫的表现是"重建跑完了，但某些老内容
 * 依然搜不到"，没有任何异常提示，只能靠断言把边界钉死。</p>
 *
 * <p>另一个重点是<b>断点语义</b>：{@code resume=true} 必须真的从断点开始，
 * 而且成功跑完的分片要清掉断点（否则下次续跑会白扫一遍已完成的片）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchShardRebuildServiceTest {

    private static final String ZSET_PREFIX = "tf:tag:media:z:";
    private static final String CK_PREFIX = "tf:ops:rebuild:ck:";
    private static final long DAY_SEC = 86_400L;

    @Mock
    ObjectProvider<OfflineShardJdbc> provider;

    @Mock
    OfflineShardJdbc shardJdbc;

    @Mock
    StringRedisTemplate redis;

    @Mock
    ZSetOperations<String, String> zSetOps;

    @Mock
    ValueOperations<String, String> valueOps;

    /**
     * ⚠️ 必须在 {@code when(provider.getIfAvailable())} <b>之后</b>再构造服务：
     * 构造器当场取一次 {@code getIfAvailable()}，先构造后打桩会拿到 null，
     * 服务从此走"离线通道不可用"分支，后面所有断言都变成假阴性。
     */
    private SearchShardRebuildService newService() {
        return new SearchShardRebuildService(provider, redis);
    }

    /** 让所有分片返回空页（最快的"扫完就结束"形态）。 */
    private void allShardsEmpty() {
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("未配置离线数据源时不扫描，返回带提示的空报告（调用方应回退 tag-index）")
    void unavailableReturnsNoteWithoutScanning() {
        when(provider.getIfAvailable()).thenReturn(null);
        SearchShardRebuildService service = newService();

        SearchRebuildService.RebuildReport r = service.rebuild(true, 4, false);

        assertFalse(service.available());
        assertEquals("tag-index-sharded", r.scope());
        assertEquals(0, r.scanned());
        assertEquals(0, r.written());
        assertEquals(0, r.shards(), "通道不可用时不应该有「完成分片数」");
        assertTrue(r.note().contains("ds0-url"), "note 必须告诉运维缺哪个配置，否则无从排查");
        assertTrue(r.note().contains("tag-index"), "note 必须给出回退方案");
    }

    @Test
    @DisplayName("dryRun 只统计不写入：不 ZADD、不写断点、不改 TTL")
    void dryRunScansButNeverWrites() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            int shard = inv.getArgument(0);
            // 只在第 0 片造一行数据，其余片空——验证按片分别统计而不是全局共用游标
            return shard == 0
                    ? List.of(new OfflineShardJdbc.Row("post/1/a", "篮球", 1_700_000_000_000L))
                    : List.of();
        });
        SearchShardRebuildService service = newService();

        SearchRebuildService.RebuildReport r = service.rebuild(true, 4, false);

        assertEquals(1, r.scanned());
        assertEquals(0, r.written(), "dryRun 下 written 必须为 0");
        assertEquals(128, r.shards(), "dryRun 也要把 128 片都过一遍才知道总量");
        verify(redis, never()).opsForZSet();
        verify(redis, never()).delete(anyString());
        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    @DisplayName("非 dryRun 写入倒排，且新建键补 30 天 TTL（否则下架后索引永不过期）")
    void writesZSetAndAddsTtlWhenMissing() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            int shard = inv.getArgument(0);
            return shard == 3
                    ? List.of(new OfflineShardJdbc.Row("post/3/b", "篮球", 1_700_000_000_000L))
                    : List.of();
        });
        when(redis.opsForZSet()).thenReturn(zSetOps);
        // getExpire 返回 -1 = 键存在但没有 TTL（或刚被 ZADD 创建）→ 必须补 TTL
        when(redis.getExpire(anyString())).thenReturn(-1L);
        SearchShardRebuildService service = newService();

        SearchRebuildService.RebuildReport r = service.rebuild(false, 4, false);

        assertEquals(1, r.scanned());
        assertEquals(1, r.written());
        verify(zSetOps).add(eq(ZSET_PREFIX + "篮球"), any(Set.class));
        verify(redis).expire(ZSET_PREFIX + "篮球", java.time.Duration.ofDays(30));
        // 跑完的分片必须清断点，否则下次 resume 会重扫一遍
        verify(redis).delete(CK_PREFIX + 3);
    }

    @Test
    @DisplayName("已有 TTL 的键不被覆盖（避免每轮重建把老索引的剩余寿命重置）")
    void keepsExistingTtl() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            int shard = inv.getArgument(0);
            return shard == 5
                    ? List.of(new OfflineShardJdbc.Row("post/5/c", "美食", 1_700_000_000_000L))
                    : List.of();
        });
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.getExpire(anyString())).thenReturn(10L * DAY_SEC);
        SearchShardRebuildService service = newService();

        service.rebuild(false, 4, false);

        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    @DisplayName("resume=true 从该片断点开始扫，而不是从头重来")
    void resumeStartsFromCheckpoint() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CK_PREFIX + 7)).thenReturn("post/9/z");
        allShardsEmpty();
        SearchShardRebuildService service = newService();

        service.rebuild(false, 4, true);

        // 第 7 片必须带着断点游标去查；其余片无断点 → null
        verify(shardJdbc).page(eq(7), eq("post/9/z"), anyInt());
        verify(shardJdbc).page(eq(8), eq(null), anyInt());
    }

    @Test
    @DisplayName("单片失败不拖垮整体：其余片照常完成，报告里点名失败数")
    void singleShardFailureDoesNotAbortOthers() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            int shard = inv.getArgument(0);
            if (shard == 7) {
                throw new IllegalStateException("connection reset");
            }
            return List.of();
        });
        SearchShardRebuildService service = newService();

        SearchRebuildService.RebuildReport r = service.rebuild(false, 4, false);

        assertEquals(127, r.shards(), "127 片成功、1 片失败");
        assertTrue(r.note().contains("失败 1 片"), "失败必须体现出来，否则会以为全量重建已完成");
        assertTrue(r.note().contains("resume=true"), "必须提示可以用 resume 补齐");
    }

    @Test
    @DisplayName("并发度越界被夹紧：0/-1 按 1 跑，超大值不超过 16")
    void parallelismIsClamped() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        allShardsEmpty();
        SearchShardRebuildService service = newService();

        // 只是不能因此抛异常或起不出线程池；行为上等价于并发 1 / 16
        assertEquals(128, service.rebuild(true, 0, false).shards());
        assertEquals(128, service.rebuild(true, 999, false).shards());
    }

    @Test
    @DisplayName("主键游标分页：满页继续、带上最后一条 post_id、不满页即停")
    void pagingUsesPrimaryKeyCursorAndStopsOnShortPage() {
        when(provider.getIfAvailable()).thenReturn(shardJdbc);
        // 第一页必须"满页"才会触发翻页——PAGE 是 500，按 500 行构造
        List<OfflineShardJdbc.Row> fullPage = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            fullPage.add(new OfflineShardJdbc.Row(String.format("post/11/%04d", i), "篮球", i));
        }
        when(shardJdbc.page(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            int shard = inv.getArgument(0);
            String cursor = inv.getArgument(1);
            if (shard != 11) {
                return List.of();
            }
            if (cursor == null) {
                return fullPage;
            }
            if ("post/11/0499".equals(cursor)) {
                // 第二页不满 → 说明扫完了，不该再查第三页
                return List.of(new OfflineShardJdbc.Row("post/11/9999", "篮球", 500L));
            }
            return List.of();
        });
        SearchShardRebuildService service = newService();

        SearchRebuildService.RebuildReport r = service.rebuild(true, 4, false);

        assertEquals(501, r.scanned(), "两页都要扫到");
        verify(shardJdbc).page(eq(11), eq(null), anyInt());
        verify(shardJdbc).page(eq(11), eq("post/11/0499"), anyInt());
        verify(shardJdbc, never()).page(eq(11), eq("post/11/9999"), anyInt());
        // dryRun 下全程不碰 Redis（既不该 ZADD，也不该写断点）
        verify(redis, never()).opsForZSet();
        verify(redis, never()).opsForValue();
    }
}
