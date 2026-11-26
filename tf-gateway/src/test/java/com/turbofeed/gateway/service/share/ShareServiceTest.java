package com.turbofeed.gateway.service.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.controller.ShortLinkController;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ShareRepository;
import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.query.MediaQueryService;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.gateway.service.social.FollowService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享补齐项单测：① 创建成功发布 SHARE 计数；② 快照带 caption（截断）；
 * ③ 落地页默认相对路径（不出现 localhost）、显式配置生效。
 */
class ShareServiceTest {

    private ShareRepository shareRepository;
    private SnowflakeIdGenerator idGenerator;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MediaJdbcRepository mediaRepository;
    private FollowService followService;
    private NotificationGenerator notificationGenerator;
    private UploadRateLimiter rateLimiter;
    private ObjectProvider<CounterEventPublisher> counterProvider;
    private CounterEventPublisher counterPublisher;
    private ShareMappingCache mappingCache;
    private ObjectProvider<ShareBloomFilter> bloomProvider;
    private ShareBloomFilter bloomFilter;
    private ShareService service;

    private static final String POST_ID = "post-share-001";
    private static final long SHARER_ID = 42L;

    @BeforeEach
    void setUp() {
        shareRepository = mock(ShareRepository.class);
        idGenerator = mock(SnowflakeIdGenerator.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        mediaRepository = mock(MediaJdbcRepository.class);
        followService = mock(FollowService.class);
        notificationGenerator = mock(NotificationGenerator.class);
        rateLimiter = mock(UploadRateLimiter.class);
        counterProvider = mock(ObjectProvider.class);
        counterPublisher = mock(CounterEventPublisher.class);
        mappingCache = mock(ShareMappingCache.class);
        bloomProvider = mock(ObjectProvider.class);
        bloomFilter = mock(ShareBloomFilter.class);
        // L1 mock 默认直调 loader（模拟缓存未命中），BF 默认不存在（关闭）
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Supplier<?> loader = inv.getArgument(1);
            return loader.get();
        }).when(mappingCache).getOrLoad(anyString(), any());
        when(bloomProvider.getIfAvailable()).thenReturn(null);

        when(idGenerator.nextId()).thenReturn(123456789L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(shareRepository.insert(any())).thenReturn(1);
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<CounterEventPublisher> consumer = invocation.getArgument(0);
            consumer.accept(counterPublisher);
            return null;
        }).when(counterProvider).ifAvailable(any());

        service = new ShareService(
                shareRepository,
                new ShareCodeGenerator("test-share-secret"),
                idGenerator,
                redisTemplate,
                new ObjectMapper(),
                mediaRepository,
                mock(MediaQueryService.class),
                followService,
                notificationGenerator,
                rateLimiter,
                counterProvider,
                mappingCache,
                bloomProvider,
                true,   // share enabled
                "");
    }

    private ShareService newService(boolean enabled, ObjectProvider<ShareBloomFilter> bfProvider) {
        return new ShareService(shareRepository, new ShareCodeGenerator("test-share-secret"),
                idGenerator, redisTemplate, new ObjectMapper(), mediaRepository,
                mock(MediaQueryService.class), followService, notificationGenerator, rateLimiter,
                counterProvider, mappingCache, bfProvider, enabled, "");
    }

    private void mockPost(String caption) {
        when(mediaRepository.findRepresentativeByPostId(POST_ID)).thenReturn(
                new MediaJdbcRepository.PostRepresentative(
                        "media-001", SHARER_ID, MediaStatus.APPROVED, "https://cdn.example.com/cover.jpg", caption));
    }

    @Test
    void createPublishesShareCountOnce() {
        mockPost("周末爬山 #户外");
        ShareService.CreateResult result = service.create(SHARER_ID, POST_ID, List.of());

        assertNotNull(result.code());
        verify(counterPublisher).incr(eq(POST_ID), eq("SHARE"), eq(1));
    }

    @Test
    void createDoesNotPublishCountWhenCounterDisabled() {
        mockPost("caption");
        org.mockito.Mockito.doNothing().when(counterProvider).ifAvailable(any());
        ShareService.CreateResult result = service.create(SHARER_ID, POST_ID, List.of());
        assertNotNull(result.code());
        // 发布器不存在时不得抛异常，分享主流程照常完成
        verify(counterPublisher, never()).incr(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void snapshotCarriesCaptionAndTruncates() throws Exception {
        String longCaption = "X".repeat(500);
        mockPost(longCaption);
        ShareService.CreateResult result = service.create(SHARER_ID, POST_ID, List.of());

        var snapshot = new ObjectMapper().readValue(result.snapshotJson(), ShareService.ShareSnapshot.class);
        assertEquals(200, snapshot.caption().length(), "caption 应截到 200 字符");
        assertEquals("https://cdn.example.com/cover.jpg", snapshot.coverUrl());
        assertEquals(SHARER_ID, snapshot.ownerId());
    }

    @Test
    void snapshotHandlesNullCaption() throws Exception {
        mockPost(null);
        ShareService.CreateResult result = service.create(SHARER_ID, POST_ID, List.of());
        var snapshot = new ObjectMapper().readValue(result.snapshotJson(), ShareService.ShareSnapshot.class);
        assertEquals("", snapshot.caption());
    }

    @Test
    void shortLinkDefaultsToRelativePath() {
        ShareService resolved = mock(ShareService.class);
        when(resolved.resolve("code123")).thenReturn(
                new ShareService.ResolveResult(POST_ID, SHARER_ID, "{}"));
        ShortLinkController controller = new ShortLinkController(resolved, "/feed.html");

        ResponseEntity<?> response = controller.redirect("code123");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        String location = response.getHeaders().getLocation().toString();
        assertTrue(location.startsWith("/feed.html?post="), "默认应为站点相对路径: " + location);
        assertTrue(!location.contains("localhost"), "不得出现 localhost: " + location);
        assertTrue(location.contains("from=share") && location.contains("trace=code123"), location);
    }

    @Test
    void shortLinkUsesExplicitFrontendUrl() {
        ShareService resolved = mock(ShareService.class);
        when(resolved.resolve("code123")).thenReturn(
                new ShareService.ResolveResult(POST_ID, SHARER_ID, "{}"));
        ShortLinkController controller = new ShortLinkController(resolved,
                "https://feed.example.com/feed.html");

        ResponseEntity<?> response = controller.redirect("code123");
        String location = response.getHeaders().getLocation().toString();
        assertTrue(location.startsWith("https://feed.example.com/feed.html?post="), location);
    }

    @Test
    void shortLinkRedirectFailsClosedWhenUnavailable() {
        ShareService resolved = mock(ShareService.class);
        when(resolved.resolve("bad")).thenReturn(null);
        ShortLinkController controller = new ShortLinkController(resolved, "/feed.html");

        ResponseEntity<?> response = controller.redirect("bad");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("该内容已不可用"));
        Mockito.verifyNoInteractions(counterPublisher);
    }

    // ===== 新增五项的单测 =====

    @Test
    void createRejectedWhenShareDisabled() {
        mockPost("caption");
        ShareService disabled = newService(false, bloomProvider);
        org.junit.jupiter.api.Assertions.assertThrows(ShareException.class,
                () -> disabled.create(SHARER_ID, POST_ID, List.of()),
                "开关关闭时创建应被拒绝");
        verify(shareRepository, never()).insert(any());
    }

    @Test
    void createWarmsL1AndAddsToBloom() {
        mockPost("caption");
        when(bloomProvider.getIfAvailable()).thenReturn(bloomFilter);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<ShareBloomFilter> c = inv.getArgument(0);
            c.accept(bloomFilter);
            return null;
        }).when(bloomProvider).ifAvailable(any());
        ShareService withBf = newService(true, bloomProvider);

        ShareService.CreateResult result = withBf.create(SHARER_ID, POST_ID, List.of());

        verify(mappingCache).getOrLoad(eq(result.code()), any());
        verify(bloomFilter).add(result.code());
    }

    @Test
    void resolveRejectsWhenBloomSaysAbsentAndEnforced() {
        when(bloomProvider.getIfAvailable()).thenReturn(bloomFilter);
        when(bloomFilter.shouldReject("randomcode")).thenReturn(true);
        ShareService withBf = newService(true, bloomProvider);

        assertEquals(null, withBf.resolve("randomcode"),
                "BF 严格模式判定不存在时直接拒绝，不查缓存/DB");
        verifyNoRedisOrDbLookup();
    }

    @Test
    void resolveIgnoresBloomWhenShadowMode() {
        when(bloomProvider.getIfAvailable()).thenReturn(bloomFilter);
        when(bloomFilter.shouldReject("code123")).thenReturn(false); // shadow 恒 false
        when(shareRepository.findByCode("code123")).thenReturn(java.util.Optional.of(
                new ShareRecord("code123", 1L, POST_ID, "media-001", SHARER_ID, SHARER_ID,
                        "{}", ShareRecord.STATUS_ACTIVE, java.time.Instant.now())));
        MediaQueryService query = mock(MediaQueryService.class);
        when(query.statusOf("media-001", String.valueOf(SHARER_ID))).thenReturn(MediaStatus.APPROVED);
        ShareService withBf = new ShareService(shareRepository, new ShareCodeGenerator("test-share-secret"),
                idGenerator, redisTemplate, new ObjectMapper(), mediaRepository,
                query, followService, notificationGenerator, rateLimiter,
                counterProvider, mappingCache, bloomProvider, true, "");

        ShareService.ResolveResult r = withBf.resolve("code123");
        assertNotNull(r, "shadow 模式 BF 不拦截，仍可解析");
    }

    // ===== 内容下架自动失效短链（§8.1）=====

    @Test
    void invalidateByPostIdRedactsAndClearsAllCodes() throws Exception {
        String snap1 = new ObjectMapper().writeValueAsString(new ShareService.ShareSnapshot(
                POST_ID, "media-001", "https://cdn.example.com/cover1.jpg", "敏感文案一",
                SHARER_ID, SHARER_ID, 1000L));
        String snap2 = new ObjectMapper().writeValueAsString(new ShareService.ShareSnapshot(
                POST_ID, "media-001", "https://cdn.example.com/cover2.jpg", "敏感文案二",
                SHARER_ID, 99L, 2000L));
        when(shareRepository.findByPostId(POST_ID)).thenReturn(List.of(
                new ShareRecord("codeA", 1L, POST_ID, "media-001", SHARER_ID, SHARER_ID,
                        snap1, ShareRecord.STATUS_ACTIVE, java.time.Instant.now()),
                new ShareRecord("codeB", 2L, POST_ID, "media-001", SHARER_ID, 99L,
                        snap2, ShareRecord.STATUS_ACTIVE, java.time.Instant.now())));
        when(shareRepository.invalidateAndRedact(anyString(), anyString())).thenReturn(1);

        int n = service.invalidateByPostId(POST_ID);

        assertEquals(2, n, "该帖两条短链都应失效");
        verify(shareRepository).invalidateAndRedact(eq("codeA"), anyString());
        verify(shareRepository).invalidateAndRedact(eq("codeB"), anyString());
        verify(mappingCache).invalidate("codeA"); // L1（含广播）
        verify(mappingCache).invalidate("codeB");
        verify(redisTemplate).delete("tf:share:code:codeA"); // L2
        verify(redisTemplate).delete("tf:share:code:codeB");

        // 快照打码：caption / coverUrl 被清空，但各自 sharerId / createdAt 保留
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(shareRepository, org.mockito.Mockito.times(2)).invalidateAndRedact(anyString(), captor.capture());
        var redacted = new ObjectMapper().readValue(captor.getAllValues().get(0), ShareService.ShareSnapshot.class);
        assertEquals("[内容已下架]", redacted.caption(), "敏感文案应打码");
        assertEquals("", redacted.coverUrl(), "封面 URL 应清空");
        assertEquals(SHARER_ID, redacted.ownerId());
        assertEquals(1000L, redacted.createdAt());
    }

    @Test
    void invalidateByPostIdSkipsBlankPostId() {
        assertEquals(0, service.invalidateByPostId(""));
        assertEquals(0, service.invalidateByPostId(null));
        verify(shareRepository, never()).findByPostId(anyString());
    }

    @Test
    void invalidateByPostIdContinuesWhenOneCodeFails() {
        when(shareRepository.findByPostId(POST_ID)).thenReturn(List.of(
                new ShareRecord("codeA", 1L, POST_ID, "media-001", SHARER_ID, SHARER_ID,
                        "{}", ShareRecord.STATUS_ACTIVE, java.time.Instant.now()),
                new ShareRecord("codeB", 2L, POST_ID, "media-001", SHARER_ID, 99L,
                        "{}", ShareRecord.STATUS_ACTIVE, java.time.Instant.now())));
        when(shareRepository.invalidateAndRedact(eq("codeA"), anyString()))
                .thenThrow(new RuntimeException("db down"));
        when(shareRepository.invalidateAndRedact(eq("codeB"), anyString())).thenReturn(1);

        int n = service.invalidateByPostId(POST_ID);

        assertEquals(1, n, "一条失败不影响另一条（fail-open）");
        verify(mappingCache).invalidate("codeB");
    }

    private void verifyNoRedisOrDbLookup() {
        verify(shareRepository, never()).findByCode(anyString());
    }
}
