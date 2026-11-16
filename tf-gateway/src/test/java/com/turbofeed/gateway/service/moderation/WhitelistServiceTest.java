package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WhitelistService} 单测（阶段 B2 补齐）：白名单对齐词库的异步单飞模型后，
 * 必须证明「写操作不阻塞调用方、新豁免最终进入快照、维度判定不回归」。
 *
 * <p>改造前本类没有任何单测，而 B2 把 admin 写路径从同步 reload 改成后台重建，
 * 没有护栏就容易出现「HTTP 返回了但豁免没生效」这类静默回归。</p>
 */
class WhitelistServiceTest {

    private static WhitelistEntry entry(long id, String word, String scene, String scope, long ownerId) {
        return new WhitelistEntry(id, word, scene, scope, ownerId, "误杀豁免", true, 1L);
    }

    private static WhitelistEntry global(String word) {
        return entry(1L, word, WhitelistEntry.SCENE_ANY, WhitelistEntry.SCOPE_GLOBAL, 0L);
    }

    @Test
    void add_triggersAsyncReloadAndSwapsIndex() throws Exception {
        WhitelistRepository repo = mock(WhitelistRepository.class);
        when(repo.checkSum()).thenReturn(new WhitelistRepository.CheckSum(1L, Instant.EPOCH));
        when(repo.findAllEnabled()).thenReturn(List.of());
        WhitelistService svc = new WhitelistService(repo);
        svc.init();
        assertFalse(svc.isExempt("新型词", ContentScene.COMMENT, 42L));

        // DB 里出现一条全局豁免，add 只写 DB + 提交后台重建
        when(repo.findAllEnabled()).thenReturn(List.of(global("新型词")));
        when(repo.checkSum()).thenReturn(new WhitelistRepository.CheckSum(1L, Instant.now()));
        when(repo.upsert(anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(global("新型词"));
        svc.add("新型词", null, null, 0L, "误杀");

        boolean exempt = false;
        for (int i = 0; i < 100; i++) {
            if (svc.isExempt("新型词", ContentScene.COMMENT, 42L)) {
                exempt = true;
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(exempt, "后台异步重建后新豁免应生效");

        svc.shutdown();
    }

    @Test
    void isExempt_respectsSceneAndScope() {
        WhitelistRepository repo = mock(WhitelistRepository.class);
        when(repo.checkSum()).thenReturn(new WhitelistRepository.CheckSum(3L, Instant.EPOCH));
        when(repo.findAllEnabled()).thenReturn(List.of(
                global("黄焖鸡"),
                entry(2L, "昵称专用", "NICKNAME", WhitelistEntry.SCOPE_GLOBAL, 0L),
                entry(3L, "用户专用", WhitelistEntry.SCENE_ANY, WhitelistEntry.SCOPE_USER, 7L)));
        WhitelistService svc = new WhitelistService(repo);
        svc.init();

        assertTrue(svc.isExempt("黄焖鸡", ContentScene.COMMENT, 999L), "全局豁免对所有场景 / 所有人生效");
        assertTrue(svc.isExempt("昵称专用", ContentScene.NICKNAME, 1L));
        assertFalse(svc.isExempt("昵称专用", ContentScene.COMMENT, 1L), "场景不匹配不得豁免");
        assertTrue(svc.isExempt("用户专用", ContentScene.CAPTION, 7L));
        assertFalse(svc.isExempt("用户专用", ContentScene.CAPTION, 8L), "用户级豁免不得放大到全站");
        assertFalse(svc.isExempt("未登记词", ContentScene.COMMENT, 7L));

        svc.shutdown();
    }

    @Test
    void writeOperations_scheduleReloadWithoutBlockingCaller() {
        WhitelistRepository repo = mock(WhitelistRepository.class);
        when(repo.checkSum()).thenReturn(new WhitelistRepository.CheckSum(0L, Instant.EPOCH));
        when(repo.findAllEnabled()).thenReturn(List.of());
        WhitelistService svc = new WhitelistService(repo);
        svc.init();
        clearInvocations(repo); // 只保留 stub，清掉启动加载的调用记录

        svc.disable(1L);
        svc.enable(1L);
        svc.delete(1L);

        verify(repo).disable(1L);
        verify(repo).enable(1L);
        verify(repo).delete(1L);
        verify(repo, timeout(2000).atLeastOnce()).findAllEnabled();

        svc.shutdown();
    }
}
