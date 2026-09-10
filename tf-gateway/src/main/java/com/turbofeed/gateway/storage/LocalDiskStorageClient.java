package com.turbofeed.gateway.storage;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.service.ImageFormat;
import com.turbofeed.shared.result.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地磁盘存储实现（默认激活）：把上传内容真实落盘，使端到端演示链路可用。
 *
 * <p><b>为什么需要它</b>：{@link PlaceholderStorageClient} 只拼装 URL 不落盘，导致
 * 前端拿到的是一个永远 404 的假地址（{@code https://oss.turbofeed.com/...}），
 * "上传 → 展示"这条最核心的演示链路无法闭合。本实现补齐落盘环节，
 * 配合 {@code WebConfig} 的 {@code /media/**} 静态资源映射，图片可被浏览器直接加载。</p>
 *
 * <p><b>路径布局</b>：{@code {localDir}/{userId}/{uuid}.{ext}}，与对象存储的 key
 * 结构完全一致——迁移到 MinIO/COS 时只需新增一个 {@code MediaStorageClient} 实现并
 * 切换 {@code turbofeed.media.storage}，业务层零改动（防腐层接口的价值落点）。</p>
 *
 * <p><b>写入安全</b>：</p>
 * <ul>
 *   <li><b>路径穿越防护</b>：userId 来自 JWT 解析（服务端签发，非客户端可控），
 *       filename 由服务端 UUID 生成而非取自客户端——杜绝 {@code ../} 穿越；
 *       仍额外做一次归一化校验，防御未来 userId 来源变更引入的风险。</li>
 *   <li><b>流式落盘</b>：{@link Files#copy} 直接消费输入流，不 {@code getBytes()}
 *       全量驻留内存，与 {@code MediaUploadService#storeOne} 的流式路径一致。</li>
 *   <li><b>写临时文件再原子改名</b>：先写 {@code .part} 再
 *       {@code ATOMIC_MOVE}，避免进程中断留下半截文件被静态映射读走。</li>
 * </ul>
 *
 * <p><b>适用边界</b>：单机演示 / 本地开发。多实例部署时本地磁盘不共享，
 * 必须切换到对象存储（MinIO/COS）——这也是本实现存在的过渡意义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.media.storage", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageClient implements MediaStorageClient {

    /** 落盘过程中的临时后缀，写完后原子改名，避免半截文件被读到。 */
    private static final String PART_SUFFIX = ".part";

    private final MediaProperties properties;

    @Override
    public StoredMedia store(String userId, ImageFormat format, InputStream content, long size) {
        // mediaId 结构与对象存储 key 保持一致：{keyPrefix}/{userId}/{uuid}.{ext}
        String mediaId = properties.getKeyPrefix() + "/" + userId + "/"
                + UUID.randomUUID() + "." + format.extension();

        Path target = resolveTargetPath(mediaId);
        try {
            Files.createDirectories(target.getParent());
            Path partFile = target.resolveSibling(target.getFileName() + PART_SUFFIX);
            try (InputStream in = content) {
                Files.copy(in, partFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("媒体落盘: mediaId={}, size={}B, path={}", mediaId, size, target);
        } catch (IOException e) {
            log.error("媒体落盘失败: mediaId={}, path={}", mediaId, target, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件写入失败");
        }
        return new StoredMedia(mediaId, properties.getPublicUrlBase() + mediaId);
    }

    /**
     * 物理删除本地文件（用户删除内容时调用）。
     *
     * <p>mediaId 即本地相对路径（{@code {userId}/{uuid}.{ext}}），经
     * {@link #resolveTargetPath} 归一化并做路径穿越校验后删除。文件不存在时
     * {@code Files.deleteIfExists} 幂等返回，不抛；仅删除失败时（权限/占用）告警。</p>
     *
     * @param mediaId 内容唯一标识（即本地相对路径）
     */
    @Override
    public void delete(String mediaId) {
        Path target = resolveTargetPath(mediaId);
        try {
            boolean removed = Files.deleteIfExists(target);
            log.info("本地文件{}: mediaId={}, path={}", removed ? "已删除" : "不存在(跳过)", mediaId, target);
        } catch (IOException e) {
            log.warn("本地文件删除失败: mediaId={}, path={}, {}", mediaId, target, e.getMessage());
        }
    }

    /**
     * 把 mediaId 解析为本地落盘路径，并做路径穿越校验。
     *
     * @throws BizException mediaId 归一化后越出存储根目录（理论上不会发生，防御性检查）
     */
    private Path resolveTargetPath(String mediaId) {
        Path root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(mediaId).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的存储路径: " + mediaId);
        }
        return target;
    }
}
