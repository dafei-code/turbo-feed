package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@link OutboxEventType#STORAGE_DELETE} 的投递处理器（PR-C1/C2）。
 *
 * <p><b>严格删除</b>：调 {@link MediaStorageClient#deleteOrThrow}，异常上抛交给发件箱
 * 退避重试 / DEAD 告警，绝不吞异常。旧 {@code delete} 那种「warn 一下继续」在处置链路里
 * 会永久留下孤儿对象——私有桶下用户看不到，只有存储账单会慢慢涨。</p>
 *
 * <p><b>幂等</b>：not-found 由 {@code MinioStorageClient#deleteOrThrow} 视为成功，
 * 因此重放 / Sweeper 重投 / 多实例重复投递都不会把事件判死。</p>
 */
@Component
public class StorageDeleteHandler implements OutboxEventHandler {

    private final ObjectMapper objectMapper;
    private final MediaStorageClient storageClient;

    public StorageDeleteHandler(ObjectMapper objectMapper, MediaStorageClient storageClient) {
        this.objectMapper = objectMapper;
        this.storageClient = storageClient;
    }

    @Override
    public OutboxEventType type() {
        return OutboxEventType.STORAGE_DELETE;
    }

    @Override
    public void handle(String payloadJson) throws Exception {
        StorageDeletePayload payload = objectMapper.readValue(payloadJson, StorageDeletePayload.class);
        if (payload == null || payload.mediaId() == null || payload.mediaId().isBlank()) {
            throw new IOException("STORAGE_DELETE 事件缺少 mediaId，拒绝投递（留待人工对账）");
        }
        // storage 字段只做审计，不按它分派实现：当前 Spring 上下文里激活的存储实现就是
        // 处置时刻那一个；若事后切了实现，删除一个不存在的同名对象也是 no-op。
        storageClient.deleteOrThrow(payload.mediaId());
    }
}
