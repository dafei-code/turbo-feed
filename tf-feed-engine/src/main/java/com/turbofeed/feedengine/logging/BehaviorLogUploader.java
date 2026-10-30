package com.turbofeed.feedengine.logging;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 行为明细归档上传端口（防腐层）：归档器只依赖此接口，具体落到 MinIO / S3 / 本地磁盘均可替换。
 *
 * <p>上传实现<b>必须</b> fail-open 友好：任何异常都抛 {@link IOException} 让归档器捕获并下个周期重试，
 * 不得让归档任务拖垮主流程或线程池。</p>
 */
public interface BehaviorLogUploader {

    /**
     * 上传一个已 gzip 压缩的本地文件到对象存储。
     *
     * @param gzFile     已压缩的本地文件（归档器负责创建与删除，实现不得持有它）
     * @param objectKey  目标对象名，格式见 {@code BehaviorLogArchiver}（{@code behavior/dt=.../hh=.../*.jsonl.gz}）
     * @throws IOException 任何传输/鉴权失败，供归档器判定"本次未成功、下个周期重试"
     */
    void upload(Path gzFile, String objectKey) throws IOException;
}
