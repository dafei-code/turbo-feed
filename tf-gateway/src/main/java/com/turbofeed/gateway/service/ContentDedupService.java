package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaDedupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 上传内容去重服务（#6 per-user SHA-256 去重，编排层）。
 *
 * <p><b>职责</b>：对上传字节算内容指纹（SHA-256），查/记指纹表，命中时返回首份的
 * canonical URL 供调用方复用（跳过对象存储写入、省空间）。所有异常 fail-open——
 * 指纹表只是优化，算哈希/查表/记表任一失败都退化为「不去重」，绝不阻断上传。</p>
 *
 * <p><b>维度</b>：{@code PER_USER}（默认）= 按 (user_id, 哈希) 查；{@code GLOBAL} = 仅靠哈希点查
 * （跨用户复用）。由 {@link MediaProperties.Dedup#getScope()} 决定。</p>
 *
 * <h3>复用模型（关键约束）</h3>
 * <p>media 表以 {@code media_id} 为<b>主键</b>，重复帖不能直接复用 canonical 的 media_id（会触发
 * ON DUPLICATE 覆盖原帖行，破坏帖结构）。因此命中时调用方应：<b>生成「别名 mediaId」</b>
 * （仍唯一）+ 复用 canonical <b>URL</b>，跳过对象存储写入。删除别名帖只删别名行、不动 canonical 对象；
 * 删原帖才删 canonical 对象（同用户 per-user 口径下副本随之失效，属 v1 接受的取舍）。</p>
 */
@Service
public class ContentDedupService {

    private static final Logger log = LoggerFactory.getLogger(ContentDedupService.class);

    private final MediaDedupRepository repository;
    private final MediaProperties mediaProperties;

    public ContentDedupService(MediaDedupRepository repository, MediaProperties mediaProperties) {
        this.repository = repository;
        this.mediaProperties = mediaProperties;
    }

    /** 去重总开关（turbofeed.media.dedup.enabled，默认 true）。 */
    public boolean isEnabled() {
        return mediaProperties.getDedup().isEnabled();
    }

    /**
     * 算内容 SHA-256（hex，64 字符）。用于字节内容指纹。
     *
     * @throws IOException 读取流失败（调用方据此降级为不去重）
     */
    public String sha256(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 强制算法，理论上不可达；仍 fail-open 抛 IOException 让调用方降级
            throw new IOException("SHA-256 不可用", e);
        }
    }

    /** 流版本（供需流式计算的场景；默认路径用 byte[] 版）。 */
    public String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    /**
     * 查指纹：命中返回 canonical（首份媒体 id + URL），未命中/关闭/异常返回 empty（fail-open）。
     *
     * <p>异常策略：查表抛异常只记 WARN（不向上抛），返回 empty —— 上传主流程不受影响，
     * 至多「这次没去重成」。</p>
     */
    public Optional<DedupHit> find(long userId, String mediaHash) {
        if (!isEnabled() || mediaHash == null || mediaHash.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<MediaDedupRepository.DedupRecord> rec = switch (mediaProperties.getDedup().getScope()) {
                case GLOBAL -> repository.findByHash(mediaHash);
                case PER_USER -> repository.findByUserHash(userId, mediaHash);
            };
            return rec.map(r -> new DedupHit(r.canonicalMediaId(), r.canonicalUrl()));
        } catch (Exception e) {
            log.warn("上传去重查表异常（退化为不去重）: userId={}, hash={}, {}", userId, mediaHash, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 记指纹（幂等，INSERT IGNORE）。失败只记 WARN（fail-open）。
     *
     * <p>调用时机：<b>未命中且已落库新对象之后</b>记录——保证 canonical 指向真实存在的对象；
     * 命中的情况不调用本方法（指纹已存在）。</p>
     */
    public void record(long userId, String mediaHash, String canonicalMediaId, String canonicalUrl) {
        if (!isEnabled() || mediaHash == null || mediaHash.isEmpty()) {
            return;
        }
        try {
            repository.insertIgnore(userId, mediaHash, canonicalMediaId, canonicalUrl);
        } catch (Exception e) {
            log.warn("上传去重记表异常（忽略，不影响上传）: userId={}, hash={}, {}",
                    userId, mediaHash, e.getMessage());
        }
    }

    /** 去重命中结果：首份媒体的 id 与 URL（调用方复用 URL、用别名 mediaId 落库）。 */
    public record DedupHit(String canonicalMediaId, String canonicalUrl) {
    }
}
