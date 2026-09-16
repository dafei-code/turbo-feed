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

    // ==================== 预签名直传扩展点（抖音式客户端直传对象存储） ====================
    //
    // 仅对象存储实现（MinioStorageClient）真正支持；本地磁盘 / 占位实现继承下面的 default，
    // 直接抛 UnsupportedOperationException。调用方须先按 turbofeed.media.storage 判定可用性，
    // 不要把不支持的实现暴露给预签名链路（否则会拿到 500 而非清晰的业务提示）。

    /**
     * 仅生成对象名（mediaId），不落盘。预签名直传下对象由客户端上传，服务端需先定名再签发凭证。
     *
     * <p><b>为什么必须服务端定名</b>：对象名即 mediaId，是后续落库主键与删除依据。
     * 若交给客户端，路径穿越与覆盖他人对象的风险无从收敛。</p>
     */
    default String generateMediaId(String userId, ImageFormat format) {
        throw presignUnsupported();
    }

    /**
     * 签发一个「限定对象名 + PUT 方法 + 有效期」的预签名上传 URL。
     *
     * @param mediaId     服务端生成的对象名
     * @param contentType 期望的内容类型（纳入签名，客户端 PUT 必须携带一致的值）
     * @param expiry      凭证有效期
     */
    default String presignedPutUrl(String mediaId, String contentType, java.time.Duration expiry) {
        throw presignUnsupported();
    }

    /** 查询对象实际字节数（完成阶段复核客户端声明的大小，防超限 / 空对象）。 */
    default long sizeOf(String mediaId) {
        throw presignUnsupported();
    }

    /**
     * 探测对象头部若干字节（完成阶段做 Magic Number 复检用）。
     *
     * <p><b>为什么不是全量下载</b>：复检只需要前 12 字节（WEBP 的标识在偏移 8 处），
     * 为 N 张图各拉全量会把「网关不收字节流」换来的带宽收益又吐回去。</p>
     */
    default byte[] probeHeader(String mediaId, int bytes) {
        throw presignUnsupported();
    }

    /** 全量下载对象（仅处理链开启时用于解码重编码）。 */
    default byte[] download(String mediaId) {
        throw presignUnsupported();
    }

    /** 覆盖写回同名对象（处理链产物回写，保持 mediaId 与 URL 不变）。 */
    default void overwrite(String mediaId, byte[] content, String contentType) {
        throw presignUnsupported();
    }

    /** 当前实现不支持预签名直传时的统一异常（default 方法共用）。 */
    private UnsupportedOperationException presignUnsupported() {
        return new UnsupportedOperationException("当前存储实现不支持预签名直传: " + getClass().getSimpleName());
    }

    /**
     * 存储结果。
     *
     * @param mediaId 内容唯一标识（形如 media/{userId}/{uuid}.{ext}），审核与查询的主键
     * @param url     可访问 URL（UGC 场景建议审核通过前不暴露公网直链）
     */
    record StoredMedia(String mediaId, String url) {
    }
}
