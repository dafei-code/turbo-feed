package com.turbofeed.feedengine.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BehaviorLogArchiver 单测（无中间件依赖：FakeUploader 验证 key 格式 / 内容 / 去重 / 保留期 / 当前小时跳过）。
 */
class BehaviorLogArchiverTest {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMdd-HH");

    /** 构造对象 key（与 BehaviorLogArchiver 同口径）：{prefix}/dt=YYYY-MM-DD/hh=HH/feed-behavior-{hour}.jsonl.gz */
    private static String keyOf(String hourBucket, String prefix) {
        String ymd = hourBucket.substring(0, 8);
        String hh = hourBucket.substring(9);
        String dt = ymd.substring(0, 4) + "-" + ymd.substring(4, 6) + "-" + ymd.substring(6, 8);
        return prefix + "/dt=" + dt + "/hh=" + hh + "/feed-behavior-" + hourBucket + ".jsonl.gz";
    }

    /** 一个"旧但仍在保留期内"的小时桶（早于当前小时，但远小于 168h 保留期），用于验证"归档后保留本地"。 */
    private static String withinRetentionHour() {
        return LocalDateTime.now().minusHours(2).format(HOUR);
    }

    /** 内存实现：捕获 objectKey + 解压后的内容，可配置"前 N 次失败"。 */
    static class FakeUploader implements BehaviorLogUploader {
        final List<String> keys = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        int failRemaining = 0;
        int callCount = 0;

        @Override
        public void upload(Path gzFile, String objectKey) throws IOException {
            callCount++;
            if (failRemaining > 0) {
                failRemaining--;
                throw new IOException("injected upload failure");
            }
            String text = decompress(gzFile);
            keys.add(objectKey);
            contents.add(text);
        }

        private static String decompress(Path gz) throws IOException {
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz));
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
    }

    /** 构造禁用的 sink（不启动后台写线程，仅借 dir() 给归档器定位目录）。 */
    private BehaviorLogSink disabledSink(Path dir) {
        return new BehaviorLogSink(new ObjectMapper(), false, dir.toString(), 100, 1000);
    }

    private BehaviorLogArchiveProperties props(String bucket, String prefix, int retentionHours) {
        BehaviorLogArchiveProperties p = new BehaviorLogArchiveProperties();
        p.setBucket(bucket);
        p.setBaseKeyPrefix(prefix);
        p.setLocalRetentionHours(retentionHours);
        return p;
    }

    private void writeJsonl(Path dir, String name, String... lines) throws IOException {
        Path f = dir.resolve(name);
        try (java.io.BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        }
    }

    @Test
    void archivesOldHourWithCorrectKeyAndContent(@TempDir Path temp) throws IOException {
        Path dir = temp.resolve("behavior");
        Files.createDirectories(dir);
        String old = withinRetentionHour();
        writeJsonl(dir, "feed-behavior-" + old + ".jsonl", "{\"a\":1}", "{\"a\":2}");

        FakeUploader up = new FakeUploader();
        BehaviorLogArchiver archiver = new BehaviorLogArchiver(
                disabledSink(dir), up, props("turbo-feed", "behavior", 168));

        archiver.archive();

        assertEquals(1, up.callCount, "应只上传一个旧文件");
        assertEquals(keyOf(old, "behavior"), up.keys.get(0));
        assertEquals("{\"a\":1}\n{\"a\":2}\n", up.contents.get(0), "gzip 解压内容须与原文一致");
        // 成功标记 + 本地保留（保留期 168h，2 小时前的文件不会清）
        assertTrue(Files.exists(dir.resolve("feed-behavior-" + old + ".jsonl.archived")), "应写 .archived 标记");
        assertTrue(Files.exists(dir.resolve("feed-behavior-" + old + ".jsonl")), "保留期内本地文件保留");
    }

    @Test
    void skipsCurrentHourButArchivesOld(@TempDir Path temp) throws IOException {
        Path dir = temp.resolve("behavior");
        Files.createDirectories(dir);
        String current = LocalDateTime.now().format(HOUR);
        writeJsonl(dir, "feed-behavior-" + current + ".jsonl", "{\"cur\":1}");
        writeJsonl(dir, "feed-behavior-20260101-00.jsonl", "{\"old\":1}");

        FakeUploader up = new FakeUploader();
        BehaviorLogArchiver archiver = new BehaviorLogArchiver(
                disabledSink(dir), up, props("b", "behavior", 168));

        archiver.archive();

        assertEquals(1, up.callCount, "当前小时文件不可变、必须跳过；只归档旧文件");
        assertEquals("behavior/dt=2026-01-01/hh=00/feed-behavior-20260101-00.jsonl.gz", up.keys.get(0));
        assertFalse(Files.exists(dir.resolve("feed-behavior-" + current + ".jsonl.archived")), "当前小时不应有标记");
    }

    @Test
    void skipsAlreadyArchivedFile(@TempDir Path temp) throws IOException {
        Path dir = temp.resolve("behavior");
        Files.createDirectories(dir);
        writeJsonl(dir, "feed-behavior-20260101-00.jsonl", "{\"x\":1}");
        Files.createFile(dir.resolve("feed-behavior-20260101-00.jsonl.archived")); // 已归档标记

        FakeUploader up = new FakeUploader();
        BehaviorLogArchiver archiver = new BehaviorLogArchiver(
                disabledSink(dir), up, props("b", "behavior", 168));

        archiver.archive();

        assertEquals(0, up.callCount, "已归档文件必须幂等跳过，不重传");
    }

    @Test
    void retentionCleansArchivedOldFile(@TempDir Path temp) throws IOException {
        Path dir = temp.resolve("behavior");
        Files.createDirectories(dir);
        writeJsonl(dir, "feed-behavior-20260101-00.jsonl", "{\"x\":1}");
        Files.createFile(dir.resolve("feed-behavior-20260101-00.jsonl.archived"));

        FakeUploader up = new FakeUploader();
        // 保留期 0 → cutoff = now，2026-01-01 的文件远早于 now，应被清理
        BehaviorLogArchiver archiver = new BehaviorLogArchiver(
                disabledSink(dir), up, props("b", "behavior", 0));

        archiver.archive();

        assertEquals(0, up.callCount, "已归档文件直接清理、不再上传");
        assertFalse(Files.exists(dir.resolve("feed-behavior-20260101-00.jsonl")), "本地 JSONL 应被清理");
        assertFalse(Files.exists(dir.resolve("feed-behavior-20260101-00.jsonl.archived")), "标记也应被清理");
    }

    @Test
    void uploadFailureRetainsLocalAndRetriesNextRun(@TempDir Path temp) throws IOException {
        Path dir = temp.resolve("behavior");
        Files.createDirectories(dir);
        String old = withinRetentionHour();
        writeJsonl(dir, "feed-behavior-" + old + ".jsonl", "{\"x\":1}");

        FakeUploader up = new FakeUploader();
        up.failRemaining = 1; // 第一次上传失败，第二次成功
        BehaviorLogArchiver archiver = new BehaviorLogArchiver(
                disabledSink(dir), up, props("b", "behavior", 168));

        archiver.archive(); // 第一次：上传失败
        assertEquals(1, up.callCount);
        assertFalse(Files.exists(dir.resolve("feed-behavior-" + old + ".jsonl.archived")), "失败不应写标记");
        assertTrue(Files.exists(dir.resolve("feed-behavior-" + old + ".jsonl")), "失败本地文件保留待重试");

        archiver.archive(); // 第二次：上传成功
        assertEquals(2, up.callCount);
        assertTrue(Files.exists(dir.resolve("feed-behavior-" + old + ".jsonl.archived")), "成功写标记");
        assertEquals(1, up.keys.size(), "只在成功那次落盘一个 key");
    }
}
