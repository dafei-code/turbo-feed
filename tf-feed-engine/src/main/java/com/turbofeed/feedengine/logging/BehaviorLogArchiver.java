package com.turbofeed.feedengine.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * 行为明细归档器（P1）：每小时把"已完成小时"的 JSONL 压缩上传 MinIO。
 *
 * <p><b>为什么按小时、且跳过当前小时</b>：{@link BehaviorLogSink} 按小时切文件，当前小时的
 * 文件仍在被后台线程追加写，整文件拷贝/上传会截到半截。归档器只处理 <b>早于当前小时</b> 的文件，
 * 保证上传的是不可变完整文件。</p>
 *
 * <p><b>幂等与可重试</b>：每个成功上传的本地文件写一个同名 {@code .archived} 空标记；已标记的文件
 * 直接跳过，因此归档任务是幂等的——MinIO 抖动/重启后在下一个周期补传即可，不会重复传也不会丢。</p>
 *
 * <p><b>fail-open</b>：单个文件上传失败只告警、不抛；不影响同批其他文件，也不影响主流程。
 * 上传失败的本地文件保留，下个周期重试（直到超过本地保留期才清理）。</p>
 *
 * <p><b>本地保留期</b>：归档成功且文件小时早于 {@code now - localRetentionHours} 时，删本地
 * {@code .jsonl} 与 {@code .archived} 标记，腾出磁盘。未归档成功的文件即使超期也保留（数据安全优先）。</p>
 *
 * <p><b>对象 key 结构</b>：{@code {baseKeyPrefix}/dt=YYYY-MM-DD/hh=HH/feed-behavior-YYYYMMDD-HH.jsonl.gz}，
 * 与 Hive/Spark 分区目录约定一致，离线任务可直接按 dt/hh 分区读。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "turbofeed.feed.behavior-log.archive", name = "enabled", havingValue = "true")
public class BehaviorLogArchiver {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMdd-HH");
    private static final Pattern FILE_NAME =
            Pattern.compile("feed-behavior-(\\d{8})-(\\d{2})\\.jsonl");
    private static final String ARCHIVED_SUFFIX = ".archived";
    private static final String TMP_SUFFIX = ".jsonl.gz.tmp";

    private final BehaviorLogSink sink;
    private final BehaviorLogUploader uploader;
    private final BehaviorLogArchiveProperties props;

    public BehaviorLogArchiver(BehaviorLogSink sink,
                               BehaviorLogUploader uploader,
                               BehaviorLogArchiveProperties props) {
        this.sink = sink;
        this.uploader = uploader;
        this.props = props;
        log.info("行为明细归档已启用: bucket={}, baseKeyPrefix={}, localRetentionHours={}, 目标端点={}",
                props.getBucket(), props.getBaseKeyPrefix(), props.getLocalRetentionHours(),
                maskEndpoint(props.getMinioEndpoint()));
    }

    /**
     * 每小时第 5 分钟跑一次（避开整点切文件的边界抖动）。
     * 仅当 {@code archive.enabled=true} 时该 Bean 存在，故方法本身无需再判开关。
     */
    @Scheduled(cron = "0 5 * * * *")
    public void archive() {
        Path dir = sink.dir();
        if (!Files.isDirectory(dir)) {
            log.warn("行为明细归档：目录不存在，跳过: {}", dir);
            return;
        }
        cleanupOrphanTmp(dir);
        String currentHour = LocalDateTime.now().format(HOUR);

        // ── 1. 归档已完成小时的文件 ──
        List<Path> candidates = listJsonl(dir);
        int archived = 0;
        int skippedCurrent = 0;
        int skippedDone = 0;
        for (Path file : candidates) {
            String name = file.getFileName().toString();
            Matcher m = FILE_NAME.matcher(name);
            if (!m.matches()) {
                continue; // 非本格式文件，忽略
            }
            String hourBucket = m.group(1) + "-" + m.group(2); // yyyyMMdd-HH
            if (hourBucket.equals(currentHour)) {
                skippedCurrent++;
                continue; // 当前小时还在写，整文件不可变前不碰
            }
            Path marker = dir.resolve(name + ARCHIVED_SUFFIX);
            if (Files.exists(marker)) {
                skippedDone++;
                continue; // 已归档，幂等跳过
            }
            try {
                archiveOne(file, m.group(1), m.group(2), marker);
                archived++;
            } catch (Exception e) {
                log.warn("行为明细归档失败（下个周期重试，本地保留）: file={}, {}", file, e.getMessage());
            }
        }

        // ── 2. 本地保留期清理 ──
        int cleaned = sweepRetention(dir);

        if (archived > 0 || cleaned > 0 || skippedCurrent > 0) {
            log.info("行为明细归档完成: 本批上传={}, 跳过当前小时={}, 跳过已归档={}, 本地清理={}",
                    archived, skippedCurrent, skippedDone, cleaned);
        }
    }

    /** 压缩 + 上传 + 写标记；任一步失败抛异常由调用方捕获。 */
    private void archiveOne(Path file, String ymd, String hh, Path marker) throws IOException {
        String dt = ymd.substring(0, 4) + "-" + ymd.substring(4, 6) + "-" + ymd.substring(6, 8);
        String objectKey = props.getBaseKeyPrefix() + "/dt=" + dt + "/hh=" + hh + "/"
                + file.getFileName() + ".gz";

        Path gz = Paths.get(file + TMP_SUFFIX);
        try (OutputStream out = Files.newOutputStream(gz);
             GZIPOutputStream gzOut = new GZIPOutputStream(out)) {
            Files.copy(file, gzOut);
        }
        try {
            uploader.upload(gz, objectKey);
            Files.deleteIfExists(gz);
            Files.createFile(marker); // 去重标记：下个周期不再重传
            log.debug("行为明细归档成功: {} -> {}", file.getFileName(), objectKey);
        } finally {
            Files.deleteIfExists(gz); // 上传失败也要清掉临时压缩文件
        }
    }

    /** 删掉上次崩溃遗留的临时压缩文件（正常路径下已删除，仅兜底）。 */
    private void cleanupOrphanTmp(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(TMP_SUFFIX))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 兜底的兜底：删不掉不影响本次归档
                        }
                    });
        } catch (IOException ignored) {
            // 目录不可列：归档循环里还会报
        }
    }

    /** 本地保留期清理：归档成功且早于 now-retention 的文件与标记一起删。 */
    private int sweepRetention(Path dir) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(props.getLocalRetentionHours());
        int removed = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path file : s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                String name = file.getFileName().toString();
                Matcher m = FILE_NAME.matcher(name);
                if (!m.matches()) {
                    continue;
                }
                Path marker = dir.resolve(name + ARCHIVED_SUFFIX);
                if (!Files.exists(marker)) {
                    continue; // 未归档成功的不清，数据安全优先
                }
                try {
                    LocalDateTime fileHour = LocalDateTime.parse(m.group(1) + "-" + m.group(2), HOUR);
                    if (fileHour.isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        Files.deleteIfExists(marker);
                        removed++;
                        log.debug("行为明细本地保留期满已清理: {}", file.getFileName());
                    }
                } catch (Exception e) {
                    log.warn("行为明细本地清理失败（下个周期重试）: file={}, {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("行为明细本地清理目录遍历失败: dir={}, {}", dir, e.getMessage());
        }
        return removed;
    }

    private List<Path> listJsonl(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jsonl") && !n.endsWith(ARCHIVED_SUFFIX);
            }).toList();
        } catch (IOException e) {
            log.warn("行为明细归档目录遍历失败: dir={}, {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** 端点脱敏：只留 host 头，不打印完整 URL（可能含凭据或内网细节）。 */
    private static String maskEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "<未配置>";
        }
        int idx = endpoint.indexOf("://");
        String host = idx >= 0 ? endpoint.substring(idx + 3) : endpoint;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host;
    }
}
