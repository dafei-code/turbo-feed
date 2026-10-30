package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link ContentSecurityService#check} 的纯单元验证（不依赖 Spring / DB / Redis）。
 *
 * <p>重点覆盖 P0 修复：决策层必须收集<b>全部</b>命中并按<b>严重度取最大动作</b>，
 * 而非用首个命中（首词定生死）裁定——后者会在「先低危后违禁」文本上漏拦。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentSecurityServiceTest {

    @Mock
    private ContentSecurityProperties properties;
    @Mock
    private SensitiveWordService sensitiveWordService;
    @Mock
    private WhitelistService whitelistService;

    @InjectMocks
    private ContentSecurityService service;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getNormalize()).thenReturn(new ContentSecurityProperties.Normalize());
    }

    private SensitiveWordHit hit(String word, String category, int start, int end) {
        return new SensitiveWordHit(word, category, start, end);
    }

    @Test
    void check_takesMostSevereAction_notFirstMatch() {
        // P0 修复场景：文本先命中低危词(LIMIT=放行) 后命中违禁词(BLOCK)。
        // 旧逻辑用首词裁定 → LIMIT → 漏拦；新逻辑取严重度最大 → BLOCK → 正确拦截。
        when(properties.isWhitelistEnabled()).thenReturn(false);
        when(properties.actionOf("MILD")).thenReturn(ModerationAction.LIMIT);
        when(properties.actionOf("BAN")).thenReturn(ModerationAction.BLOCK);
        when(sensitiveWordService.matchAll(anyString()))
                .thenReturn(List.of(hit("mildword", "MILD", 0, 8), hit("违禁", "BAN", 9, 11)));

        ContentVerdict v = service.check(ContentScene.COMMENT, "mildword 违禁", 0L);

        assertTrue(v.denies());
        assertEquals(ModerationAction.BLOCK, v.action());
        assertEquals("违禁", v.matchedWord());
    }

    @Test
    void check_singleBlockHit_denies() {
        when(properties.isWhitelistEnabled()).thenReturn(false);
        when(properties.actionOf("BAN")).thenReturn(ModerationAction.BLOCK);
        when(sensitiveWordService.matchAll(anyString())).thenReturn(List.of(hit("违禁", "BAN", 0, 2)));

        ContentVerdict v = service.check(ContentScene.COMMENT, "违禁内容", 0L);
        assertTrue(v.denies());
        assertEquals(ModerationAction.BLOCK, v.action());
    }

    @Test
    void check_allWhitelisted_returnsExempt() {
        // 白名单只减不增：全部命中都被豁免 → 整体放行（exempt），仍保留样本词
        when(properties.isWhitelistEnabled()).thenReturn(true);
        when(properties.actionOf(anyString())).thenReturn(ModerationAction.BLOCK);
        when(whitelistService.isExempt(anyString(), any(), anyLong())).thenReturn(true);
        when(sensitiveWordService.matchAll(anyString()))
                .thenReturn(List.of(hit("违禁", "BAN", 0, 2), hit("广告", "BAN", 3, 5)));

        ContentVerdict v = service.check(ContentScene.COMMENT, "违禁 广告", 0L);
        assertFalse(v.denies());
        assertTrue(v.whitelisted());
        assertEquals("违禁", v.matchedWord());
    }

    @Test
    void check_partialWhitelist_stillBlocksOnRemaining() {
        // 一个词被豁免(LIMIT)，另一个未被豁免(BLOCK) → 取 BLOCK 裁定，白名单计一次
        when(properties.isWhitelistEnabled()).thenReturn(true);
        when(properties.actionOf("MILD")).thenReturn(ModerationAction.LIMIT);
        when(properties.actionOf("BAN")).thenReturn(ModerationAction.BLOCK);
        when(whitelistService.isExempt("mildword", ContentScene.COMMENT, 0L)).thenReturn(true);
        when(whitelistService.isExempt("违禁", ContentScene.COMMENT, 0L)).thenReturn(false);
        when(sensitiveWordService.matchAll(anyString()))
                .thenReturn(List.of(hit("mildword", "MILD", 0, 8), hit("违禁", "BAN", 9, 11)));

        ContentVerdict v = service.check(ContentScene.COMMENT, "mildword 违禁", 0L);
        assertTrue(v.denies());
        assertFalse(v.whitelisted());
        assertEquals(ModerationAction.BLOCK, v.action());
    }

    @Test
    void check_noHit_passes() {
        when(properties.isWhitelistEnabled()).thenReturn(false);
        when(sensitiveWordService.matchAll(anyString())).thenReturn(List.of());

        ContentVerdict v = service.check(ContentScene.COMMENT, "正常内容", 0L);
        assertFalse(v.denies());
        assertEquals(ModerationAction.PASS, v.action());
    }

    @Test
    void check_disabled_passesWithoutMatching() {
        when(properties.isEnabled()).thenReturn(false);
        ContentVerdict v = service.check(ContentScene.COMMENT, "违禁", 0L);
        assertFalse(v.denies());
    }

    @Test
    void severity_ordering_blockHighest() {
        assertTrue(ModerationAction.BLOCK.severity() > ModerationAction.REVIEW.severity());
        assertTrue(ModerationAction.REVIEW.severity() > ModerationAction.LIMIT.severity());
        assertTrue(ModerationAction.LIMIT.severity() > ModerationAction.PASS.severity());
    }
}
