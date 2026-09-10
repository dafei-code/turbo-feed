package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.service.ImageFormat;

import java.io.InputStream;

/**
 * 媒体存储端口（防腐层接口）：业务层只依赖本接口，不感知具体存储实现。
 *
 * <p>演进规划：当前为占位实现（不落盘，见 {@code PlaceholderStorageClient}）；
 * MinIO 接入时新增 {@code MinioStorageClient} 实现并按配置切换激活——接口契约不变，
 * Service 层零改动（这正是 S3 协议抽象价值在代码层的落点）。
 * 入参为流式内容，避免大文件全量驻留内存。</p>
 */
public interface MediaStorageClient {

    /**
     * 存储一份已通过校验的上传内容，返回唯一标识与可访问 URL。
     *
     * @param userId  归属用户（来自 JWT 解析，存储 key 按用户隔离，客户端无法指定他人）
     * @param format  已校验的真实图片格式（Magic Number 判定）
     * @param content 文件内容流（由调用方负责关闭，实现须在方法返回前完成消费）
     * @param size    内容字节数
     */
    StoredMedia store(String userId, ImageFormat format, InputStream content, long size);

    /**
     * 物理删除已存储的内容（用户删除时调用）。
     *
     * <p>实现须按 {@code mediaId} 定位并移除底层对象：MinIO 走 {@code removeObject}，
     * 本地磁盘走 {@code Files.deleteIfExists}。删除失败（对象已不存在等）由调用方决定
     * 是否继续逻辑删除，本接口不强制抛异常——实现内部 catch 后仅告警即可。</p>
     *
     * @param mediaId 内容唯一标识（形如 media/{userId}/{uuid}.{ext}，即存储 key / 对象名）
     */
    void delete(String mediaId);

    /**
     * 存储结果。
     *
     * @param mediaId 内容唯一标识（形如 media/{userId}/{uuid}.{ext}），审核与查询的主键
     * @param url     可访问 URL（UGC 场景建议审核通过前不暴露公网直链）
     */
    record StoredMedia(String mediaId, String url) {
    }
}
