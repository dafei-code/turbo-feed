package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.gateway.storage.MediaStorageClient;
import com.turbofeed.gateway.storage.MinioStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-C1/C2：严格删除语义 + STORAGE_DELETE 事件处理器的行为护栏。
 *
 * <p>最关键的一条：not-found 必须幂等成功，否则 at-least-once 重放会把正常的重复投递判成 DEAD。</p>
 */
class StorageDeleteHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void payload_roundTrip_keepsOccurredAtAndTraceId() throws Exception {
        Instant now = Instant.parse("2026-11-16T08:00:00Z");
        String json = mapper.writeValueAsString(
                new StorageDeletePayload("media/42/a.jpg", "minio", now, "trace-1"));
        assertTrue(json.contains("\"mediaId\":\"media/42/a.jpg\""));
        assertTrue(json.contains("\"traceId\":\"trace-1\""));
        StorageDeletePayload back = mapper.readValue(json, StorageDeletePayload.class);
        assertEquals("minio", back.storage());
        assertEquals(now, back.occurredAt());
        assertEquals("trace-1", back.traceId());
    }

    @Test
    void legacyPayload_withoutNewFields_isTolerated() throws Exception {
        StorageDeletePayload p = mapper.readValue(
                "{\"mediaId\":\"media/42/a.jpg\",\"storage\":\"minio\"}", StorageDeletePayload.class);
        assertNull(p.occurredAt());
        assertNull(p.traceId());
        assertEquals("media/42/a.jpg", p.mediaId());
    }

    @Test
    void handler_callsStrictDelete() throws Exception {
        MediaStorageClient storage = mock(MediaStorageClient.class);
        StorageDeleteHandler handler = new StorageDeleteHandler(mapper, storage);
        String json = mapper.writeValueAsString(new StorageDeletePayload("media/42/a.jpg", "minio"));

        handler.handle(json);

        assertEquals(OutboxEventType.STORAGE_DELETE, handler.type());
        verify(storage).deleteOrThrow("media/42/a.jpg");
        verify(storage, never()).delete(any());
    }

    @Test
    void handler_missingMediaId_throws_soOutboxRetries() {
        MediaStorageClient storage = mock(MediaStorageClient.class);
        StorageDeleteHandler handler = new StorageDeleteHandler(mapper, storage);

        assertThrows(Exception.class, () -> handler.handle("{\"storage\":\"minio\"}"));
    }

    @Test
    void handler_deleteFailure_propagates_neverSilentlyMarksSent() throws Exception {
        MediaStorageClient storage = mock(MediaStorageClient.class);
        doThrow(new IllegalStateException("minio down")).when(storage).deleteOrThrow("media/42/a.jpg");
        StorageDeleteHandler handler = new StorageDeleteHandler(mapper, storage);

        assertThrows(IllegalStateException.class,
                () -> handler.handle(mapper.writeValueAsString(
                        new StorageDeletePayload("media/42/a.jpg", "minio"))));
    }

    @Test
    void defaultDeleteOrThrow_bridgesToLegacyDelete() throws Exception {
        // 本地磁盘 / 占位实现不覆盖严格入口，默认桥接保持「天然幂等不抛」。
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        MediaStorageClient local = new MediaStorageClient() {
            @Override
            public StoredMedia store(String userId,
                                     com.turbofeed.gateway.service.ImageFormat format,
                                     java.io.InputStream content, long size) {
                return null;
            }

            @Override
            public void delete(String mediaId) {
                calls.incrementAndGet();
            }
        };
        assertDoesNotThrow(() -> local.deleteOrThrow("media/42/a.jpg"));
        assertEquals(1, calls.get(), "默认实现必须桥接到旧 delete");
    }

    @Test
    void minio_deleteOrThrow_successAndNotFoundAreIdempotent() {
        // 正常删除
        assertDoesNotThrow(() -> minio(null, false).deleteOrThrow("media/42/a.jpg"));
        // not-found（重放场景）：必须当成成功，不能让发件箱重试/判死
        assertDoesNotThrow(() -> minio(new IllegalStateException("already gone"), true)
                .deleteOrThrow("media/42/a.jpg"));
    }

    @Test
    void minio_deleteOrThrow_otherFailure_propagates() {
        MinioStorageClient client = minio(new IllegalStateException("connection reset"), false);
        assertThrows(IllegalStateException.class, () -> client.deleteOrThrow("media/42/a.jpg"));
    }

    @Test
    void minio_legacyDelete_stillSwallows_forBestEffortPaths() {
        // 启动清理 / 孤儿回收仍用 best-effort 口径，PR-C1 不改它们。
        assertDoesNotThrow(() -> minio(new IllegalStateException("connection reset"), false)
                .delete("media/42/a.jpg"));
    }

    /** 构造一个把底层 IO 换成桩的 MinIO 客户端：不需要起真 MinIO，也不碰 SDK 异常构造器。 */
    private static MinioStorageClient minio(Exception failure, boolean notFound) {
        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        return new MinioStorageClient(null, env) {
            @Override
            protected void removeObject(String mediaId) throws Exception {
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            protected boolean isNotFound(Exception e) {
                return notFound;
            }
        };
    }
}
