package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SensitiveWordService} 单测：重点覆盖 P0 之后的「不停机动态添加」——
 * admin 写入只写 DB 并触发<b>后台异步</b>重建，HTTP 线程不阻塞，重建在单飞后台线程
 * 完成并原子切换 {@code dictRef}，新词随后即可被 {@link #matchAll} 命中。
 */
class SensitiveWordServiceTest {

    @Test
    void add_triggersAsyncReloadAndSwapsDictionary() throws Exception {
        // 真实 dictRef + 真实后台单飞线程池；repository 用 mock 提供词库，避免依赖 DB
        SensitiveWordRepository repo = mock(SensitiveWordRepository.class);
        when(repo.checkSum()).thenReturn(new SensitiveWordRepository.CheckSum(1L, Instant.EPOCH));

        SensitiveWordService svc = new SensitiveWordService(repo);
        svc.init(); // 同步首加载（@PostConstruct 在单测里手动触发）

        // 模拟「动态加一个新词」：DB 返回新词库，admin 调 add（只写 DB + 触发后台重建）
        when(repo.findAllEnabled()).thenReturn(List.of(new SensitiveWord(2L, "新型词", "BAN", true, 1L)));
        when(repo.checkSum()).thenReturn(new SensitiveWordRepository.CheckSum(1L, Instant.now()));
        svc.add("新型词", "BAN");

        // 等待后台线程把新快照 swap 进 dictRef（HTTP 线程此刻早已返回，不阻塞）
        SensitiveWordHit hit = null;
        for (int i = 0; i < 100; i++) {
            List<SensitiveWordHit> hits = svc.matchAll("这里含新型词");
            if (!hits.isEmpty()) {
                hit = hits.get(0);
                break;
            }
            Thread.sleep(20);
        }
        assertNotNull(hit, "后台异步重建后新词应可被匹配到");
        assertEquals("新型词", hit.word());

        svc.shutdown();
    }

    @Test
    void matchAll_delegatesToAllMatchesInOrder() {
        SensitiveWordRepository repo = mock(SensitiveWordRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                new SensitiveWord(1L, "ab", "MILD", true, 1L),
                new SensitiveWord(2L, "bc", "MILD", true, 1L),
                new SensitiveWord(3L, "abc", "BAN", true, 1L)));
        when(repo.checkSum()).thenReturn(new SensitiveWordRepository.CheckSum(3L, Instant.EPOCH));

        SensitiveWordService svc = new SensitiveWordService(repo);
        svc.init();

        List<SensitiveWordHit> hits = svc.matchAll("abc");
        // 全部命中：ab(0,2) / abc(0,3) / bc(1,3)，顺序与 AhoCorasick.allMatches 一致
        assertEquals(3, hits.size());
        assertEquals("ab", hits.get(0).word());
        assertEquals("abc", hits.get(1).word());
        assertEquals("bc", hits.get(2).word());

        svc.shutdown();
    }
}
