package com.turbofeed.feedengine.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedBehaviorEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行为埋点<b>原始明细</b>落盘（M0：训练样本的唯一来源）。
 *
 * <p><b>为什么必须有它</b>：在此之前行为事件只用于累加 Redis 计数器
 * （{@code tf:post:stat:*} 与兴趣画像），<b>聚合完即丢弃</b>——
 * 计数能回答"这条内容有多少赞"，但回答不了"哪个用户在什么位置看到了什么、点了没点"。
 * 后者才是训练样本。没有明细落盘，双塔召回 / 精排模型 / 特征存储 / 粗排截断<b>四项全部悬空</b>。</p>
 *
 * <p><b>为什么先落文件而不是直接进 ClickHouse/Kafka</b>：
 * <ul>
 *   <li><b>零新中间件</b>：项目当前只有 MySQL + Redis + RocketMQ，再加一列式存储是运维负担；
 *       JSONL 是"最容易被任何下游吃掉"的格式（{@code clickhouse-local}、Spark、Python 均可直接读）。</li>
 *   <li><b>解耦</b>：落盘与"导入哪里"是两个决策，先保证明细不丢，导入通道以后换也不影响上游。</li>
 *   <li><b>可验证</b>：文件能直接被校验脚本读回（见 {@code target/verify/BehaviorLogVerify}），
 *       落 ClickHouse 则难以在单机上做闭环断言。</li>
 * </ul>
 * 代价：单机文件不保证多副本，需靠后续导入到对象存储/数仓兜住持久性——这是已知取舍，不是遗漏。</p>
 *
 * <p><b>异步 + 有界队列 + 丢得起的语义</b>：埋点绝不能拖慢主流程。
 * 队列满时<b>直接丢弃并计数</b>（{@link #dropped()}），不阻塞调用方、不抛异常；
 * 丢埋点损失的是样本量，卡住请求损失的可用性——取舍明确偏向后者。</p>
 *
 * <p><b>默认关闭</b>（{@code turbofeed.feed.behavior-log.enabled=false}）：
 * 落盘是"为了攒数据"的行为，默认不该在任何人的机器上静默写文件。
 * 需要采集时显式打开，并自行负责磁盘与清理。</p>
 *
 * <p><b>fail-open 的边界</b>：本类内部所有异常都只告警；但<b>调用方的正确性不依赖它</b>——
 * 统计（{@code PostStatService}）与画像（{@code InterestService}）各自独立写入，
 * 本类挂掉不影响它们。</p>
 */
@Service
public class BehaviorLogSink {

    private static final Logger log = LoggerFactory.getLogger(BehaviorLogSink.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String FILE_PREFIX = "feed-behavior-";
    private static final String FILE_SUFFIX = ".jsonl";

    private final boolean enabled;
    private final Path dir;
    private final int queueCapacity;
    private final long flushIntervalMillis;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<String> queue;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** 落盘工作线程（单线程；仅当 enabled 时启动）。 */
    private volatile Thread worker;
    private volatile boolean running;
    private volatile BufferedWriter writer;
    private volatile String currentDay;
    /**
     * 显式 flush 请求（由 {@link #flush()} 置位、工作线程消费）。
     *
     * <p><b>为什么不直接在工作线程外调用 {@code writer.flush()}</b>：文件句柄只归工作线程所有，
     * 外部线程碰它等于引入共享可变状态（还有并发 close 的风险）。置位让工作线程自己 flush，
     * 锁与生命周期都留在单一线程内。</p>
     */
    private volatile boolean flushRequested;

    public BehaviorLogSink(ObjectMapper objectMapper,
                           @Value("${turbofeed.feed.behavior-log.enabled:false}") boolean enabled,
                           @Value("${turbofeed.feed.behavior-log.dir:logs/behavior}") String dir,
                           @Value("${turbofeed.feed.behavior-log.queue-capacity:10000}") int queueCapacity,
                           @Value("${turbofeed.feed.behavior-log.flush-interval-millis:1000}") long flushIntervalMillis) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.dir = Paths.get(dir);
        this.queueCapacity = queueCapacity;
        this.flushIntervalMillis = flushIntervalMillis;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        if (enabled) {
            start();
        } else {
            log.info("行为明细落盘已关闭（turbofeed.feed.behavior-log.enabled=false）：不写文件");
        }
    }

    /** 记录单条行为事件（异步；队列满则丢弃）。null 直接忽略。 */
    public void log(FeedBehaviorEvent event) {
        if (!enabled || event == null) {
            return;
        }
        String line;
        try {
            line = toJsonLine(event);
        } catch (Exception e) {
            // 序列化失败不应影响后续事件，也不应冒泡到调用方
            dropped.incrementAndGet();
            warnOnce("行为明细序列化失败并丢弃: {}", e.getMessage());
            return;
        }
        if (!queue.offer(line)) {
            dropped.incrementAndGet();
            warnOnce("行为明细落盘队列已满（capacity={}），丢弃埋点; 累计丢弃={}", queueCapacity, dropped.get());
        }
    }

    /** 批量记录（与 {@link #log(FeedBehaviorEvent)} 同语义，逐条入队）。 */
    public void logAll(List<FeedBehaviorEvent> events) {
        if (events == null) {
            return;
        }
        for (FeedBehaviorEvent e : events) {
            log(e);
        }
    }

    /** 已落盘行数（用于校验/观测；非精确实时，工作线程写出后递增）。 */
    public long written() {
        return written.get();
    }

    /** 因队列满或序列化失败被丢弃的行数。 */
    public long dropped() {
        return dropped.get();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 请求把已入队的内容刷到磁盘（异步生效：由工作线程在下一轮循环执行）。
     *
     * <p>用途：优雅停机前的主动 flush、人工/脚本触发的"立刻可见"（例如端到端验证时
     * 需要马上读文件断言）。正常路径依赖 {@code flush-interval-millis} 周期刷即可。</p>
     */
    public void flush() {
        flushRequested = true;
    }

    /** 当前落盘目录（校验脚本据此读文件）。 */
    public Path dir() {
        return dir;
    }

    private void start() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("行为明细落盘目录创建失败，落盘功能停用: dir={}, {}", dir, e.getMessage());
            return;
        }
        running = true;
        worker = new Thread(this::drainLoop, "behavior-log-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("行为明细落盘已启用: dir={}, queueCapacity={}, flushIntervalMillis={}", dir, queueCapacity, flushIntervalMillis);
    }

    private void drainLoop() {
        long lastFlush = System.currentTimeMillis();
        while (running) {
            try {
                String line = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line != null) {
                    append(line);
                    written.incrementAndGet();
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= flushIntervalMillis || flushRequested) {
                    flushQuietly();
                    flushRequested = false;
                    lastFlush = now;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                warnOnce("行为明细落盘异常: {}", e.getMessage());
            }
        }
        // 退出前把队列里剩下的尽量写完（关闭/重启时避免尾部丢失）
        String line;
        while ((line = queue.poll()) != null) {
            append(line);
            written.incrementAndGet();
        }
        closeQuietly();
    }

    private void append(String line) {
        try {
            BufferedWriter w = writerFor(LocalDate.now().format(DAY));
            if (w == null) {
                dropped.incrementAndGet();
                return;
            }
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            dropped.incrementAndGet();
            warnOnce("行为明细写入失败: {}", e.getMessage());
        }
    }

    /** 按天切换文件；返回 null 表示打开失败（调用方按丢弃处理）。 */
    private BufferedWriter writerFor(String day) throws IOException {
        if (writer != null && day.equals(currentDay)) {
            return writer;
        }
        closeQuietly();
        Path file = dir.resolve(FILE_PREFIX + day + FILE_SUFFIX);
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        writer = w;
        currentDay = day;
        return w;
    }

    private void flushQuietly() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException e) {
            warnOnce("行为明细 flush 失败: {}", e.getMessage());
        }
    }

    private void closeQuietly() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            warnOnce("行为明细文件关闭失败: {}", e.getMessage());
        } finally {
            writer = null;
            currentDay = null;
        }
    }

    @PreDestroy
    public void close() {
        running = false;
        Thread w = worker;
        if (w != null) {
            try {
                w.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        closeQuietly();
        log.info("行为明细落盘已停止: written={}, dropped={}", written.get(), dropped.get());
    }

    /**
     * 事件 → 一行 JSON。字段顺序固定（便于下游按位置解析也稳定）。
     *
     * <p><b>刻意不落 score</b>：排序分随模型/权重版本变化，历史不可比；
     * 训练时应由特征重算得到（见 {@link FeedBehaviorEvent} 的说明）。</p>
     */
    String toJsonLine(FeedBehaviorEvent e) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", System.currentTimeMillis());
        m.put("userId", e.userId());
        m.put("timelineKey", e.timelineKey());
        m.put("type", e.type());
        m.put("watchDuration", e.watchDuration());
        m.put("mediaDuration", e.mediaDuration());
        m.put("requestId", e.requestId());
        m.put("position", e.position());
        return objectMapper.writeValueAsString(m);
    }

    private long lastWarn = 0;

    /** 告警降频：队列满时每条都 warn 会瞬间刷爆日志，这里 5s 内最多一条。 */
    private void warnOnce(String template, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 5000) {
            lastWarn = now;
            log.warn(template, args);
        }
    }
}
