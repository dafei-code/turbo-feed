package com.turbofeed.feedengine.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.recall.EmbeddingCodec;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 向量 HNSW 索引读写器（路线 A：Redis Stack / RediSearch 亚线性 ANN）。
 *
 * <p><b>为什么单独抽出来</b>：RediSearch 的 {@code VECTOR} 字段只认<b>原始 FLOAT32 小端二进制</b>，
 * 而 {@link StringRedisTemplate} 按 UTF-8 序列化会把二进制破坏。因此本类的二进制写入
 * （HSET vec / FT.CREATE）经 {@code RedisConnection} 原生命令走字节；FT.SEARCH 因回复是
 * 「含 long 的嵌套结构 / RESP3 map」，需经 Lettuce 异步门面 {@code RedisAsyncCommands} +
 * {@code NestedMultiOutput} 解码。<b>不引入任何外部依赖</b>（Lettuce 随 spring-data-redis 引入）。</p>
 *
 * <p><b>数据模型</b>：每件内容除写 {@code tf:emb:item:{timelineKey}}（base64，兼容暴力余弦回退）外，
 * 额外写 hash {@code tf:vec:item:{timelineKey}}：
 * <ul>
 *   <li>{@code vec} 字段 = 原始 FLOAT32 二进制（HNSW 索引源，仅 RediSearch 读）</li>
 *   <li>{@code payload} 字段 = FeedItemView JSON（召回时重建对象用，UTF-8 安全）</li>
 * </ul>
 * 在 plain redis（无 RediSearch）上 HSET 仍可用（hash 只是不可被 KNN 检索），FT 命令 fail-open。</p>
 *
 * <p><b>fail-open 总基调</b>：索引缺失/模块不可用 → 所有方法返回空或告警，绝不抛到调用方阻断召回。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Component
public class VectorIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWriter.class);
    private static final String VEC_FIELD = "vec";
    private static final String PAYLOAD_FIELD = "payload";

    /** FT.SEARCH 是 RediSearch 模块命令，不在 Lettuce 内置 CommandType 枚举里 → 自定义 ProtocolKeyword。 */
    private static final ProtocolKeyword FT_SEARCH = new ProtocolKeyword() {
        @Override
        public byte[] getBytes() {
            return "FT.SEARCH".getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String name() {
            return "FT.SEARCH";
        }
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int dim;
    private final String indexName;
    private final String vecPrefix;

    public VectorIndexWriter(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             @Value("${turbofeed.feed.recall.vector.dim:64}") int dim,
                             @Value("${turbofeed.feed.recall.vector.ann-index:tfVecIdx}") String indexName,
                             @Value("${turbofeed.feed.recall.vector.vec-prefix:tf:vec:item:}") String vecPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.dim = dim;
        this.indexName = indexName;
        this.vecPrefix = vecPrefix;
    }

    /** 索引名（供探针/运维查看）。 */
    public String indexName() {
        return indexName;
    }

    /** hash 全键（tf:vec:item:{timelineKey}）。 */
    public String vecKey(String timelineKey) {
        return vecPrefix + timelineKey;
    }

    /**
     * 幂等建 HNSW 索引（RediSearch 模块；plain redis 上失败 fail-open）。
     * 已存在则 FT.CREATE 报错，捕获忽略。
     */
    public void ensureIndex() {
        try {
            redis.execute((RedisCallback<Void>) conn -> {
                byte[][] args = {
                        indexName.getBytes(StandardCharsets.UTF_8),
                        "ON".getBytes(StandardCharsets.UTF_8), "HASH".getBytes(StandardCharsets.UTF_8),
                        "PREFIX".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8),
                        vecPrefix.getBytes(StandardCharsets.UTF_8),
                        "SCHEMA".getBytes(StandardCharsets.UTF_8),
                        VEC_FIELD.getBytes(StandardCharsets.UTF_8),
                        "VECTOR".getBytes(StandardCharsets.UTF_8), "HNSW".getBytes(StandardCharsets.UTF_8),
                        "6".getBytes(StandardCharsets.UTF_8),
                        "TYPE".getBytes(StandardCharsets.UTF_8), "FLOAT32".getBytes(StandardCharsets.UTF_8),
                        "DIM".getBytes(StandardCharsets.UTF_8), String.valueOf(dim).getBytes(StandardCharsets.UTF_8),
                        "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8), "COSINE".getBytes(StandardCharsets.UTF_8)
                };
                conn.execute("FT.CREATE", args);
                return null;
            });
            log.info("向量 HNSW 索引已创建/已存在: {}", indexName);
        } catch (Exception e) {
            log.warn("向量 HNSW 索引创建失败（需 Redis Stack/RediSearch 模块；fail-open 退回暴力余弦）: {}", e.getMessage());
        }
    }

    /**
     * 写 item 向量 hash（vec=原始 FLOAT32 二进制供 RediSearch；payload=FeedItemView JSON 供召回重建）。
     * plain redis 上亦安全（仅 HSET，不可被 KNN 检索）。
     */
    public void writeItem(String timelineKey, double[] vec, FeedItemView view) {
        if (timelineKey == null || vec == null || view == null) {
            return;
        }
        try {
            byte[] vecBytes = EmbeddingCodec.toFloat32Bytes(vec);
            String payload = objectMapper.writeValueAsString(view);
            byte[] key = vecKey(timelineKey).getBytes(StandardCharsets.UTF_8);
            redis.execute((RedisCallback<Void>) conn -> {
                conn.hSet(key, VEC_FIELD.getBytes(StandardCharsets.UTF_8), vecBytes);
                conn.hSet(key, PAYLOAD_FIELD.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8));
                return null;
            });
        } catch (Exception e) {
            log.warn("向量 hash 写入失败（fail-open）: key={}, {}", timelineKey, e.getMessage());
        }
    }

    /**
     * KNN 查询：返回 TopK 的 hash 全键（tf:vec:item:{timelineKey}）。
     * 无模块/异常 → 空列表（调用方 fail-open 退回暴力余弦）。
     */
    public List<String> knnKeys(byte[] queryVec, int k) {
        if (queryVec == null || k <= 0) {
            log.warn("KNN: 参数非法 queryVecNull={}, k={}", queryVec == null, k);
            return List.of();
        }
        try {
            // ⚠️ 不能走 conn.execute("FT.SEARCH", args)：spring-data-redis 的通用执行器用 ByteArrayOutput
            // 解码，而 FT.SEARCH 返回「总数(long) + 交替[key, fields...]」的嵌套数组，遇到 long 会抛
            // "ByteArrayOutput does not support set(long)"。
            // ⚠️ 也不能用 instanceof LettuceConnection：spring-data-redis 会把连接包成 JDK 动态代理
            // （jdk.proxy2.$Proxy...），代理不满足该判断，会静默返回空。
            // 正解：conn.getNativeConnection()（代理会委托到真实连接）拿到 Lettuce 的异步命令门面
            // RedisAsyncCommands，用它按 ProtocolKeyword 下发自定义命令 + NestedMultiOutput 解码，
            // 并用 future.get() 阻塞取结果（dispatch 本身是异步的）。
            Object raw = redis.execute((RedisCallback<Object>) conn -> {
                Object nativeObj = conn.getNativeConnection();
                if (!(nativeObj instanceof RedisAsyncCommands<?, ?> asyncRaw)) {
                    log.warn("KNN: 非 Lettuce 异步命令门面（类型={}），无法执行 FT.SEARCH",
                            nativeObj == null ? "null" : nativeObj.getClass().getName());
                    return List.of();
                }
                @SuppressWarnings("unchecked")
                RedisAsyncCommands<byte[], byte[]> async =
                        (RedisAsyncCommands<byte[], byte[]>) asyncRaw;
                var codec = new ByteArrayCodec();
                CommandArgs<byte[], byte[]> args = new CommandArgs<>(codec);
                args.add(indexName.getBytes(StandardCharsets.UTF_8));
                args.add(("*=>[KNN " + k + " @vec $q]").getBytes(StandardCharsets.UTF_8));
                // ⚠️ RediSearch 的 PARAMS n 中 n 是「token 数」（必须为偶数 = name+value 成对），
                // 不是参数对数。单个向量参数 = (q, <vec>) 共 2 个 token → n=2。
                args.add("PARAMS".getBytes(StandardCharsets.UTF_8));
                args.add("2".getBytes(StandardCharsets.UTF_8));
                args.add("q".getBytes(StandardCharsets.UTF_8));
                args.add(queryVec);
                args.add("DIALECT".getBytes(StandardCharsets.UTF_8));
                args.add("2".getBytes(StandardCharsets.UTF_8));
                NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(codec);
                RedisFuture<List<Object>> future = async.dispatch(FT_SEARCH, output, args);
                try {
                    return future.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return List.of();
                } catch (ExecutionException ee) {
                    // 命令执行出错（如 RediSearch 语法错误）：包装成非受查异常交给外层 fail-open 处理
                    throw new IllegalStateException(ee.getCause() != null ? ee.getCause() : ee);
                }
            });
            return parseKeys(raw);
        } catch (Exception e) {
            log.warn("HNSW KNN 查询失败（fail-open，退回暴力余弦）: {} | cause={} | class={}",
                    e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "none",
                    e.getClass().getName());
            return List.of();
        }
    }

    /**
     * 解析 FT.SEARCH 回复，兼容两种协议形态：
     * <ul>
     *   <li><b>RESP3</b>（Lettuce 6 默认，Redis 6+/Stack 支持）：回复是 map，
     *       {@code {format:..., results:[{id:<key>, score:..., extra_attributes:{...}}, ...],
     *       total_results:N, warning:[]}}，扁平化为 {@code [format, STRING, results, <docs>, ...]}。</li>
     *   <li><b>RESP2</b>：回复是扁平数组 {@code [总数, key1, [fields1], key2, [fields2], ...]}。
     * </ul>
     */
    private List<String> parseKeys(Object raw) {
        List<String> keys = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) {
            return keys;
        }
        // 先看是否是 RESP3 的 map：含 "results" 标记
        int resultsIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if ("results".equals(asString(rows.get(i)))) {
                resultsIdx = i;
                break;
            }
        }
        if (resultsIdx >= 0 && resultsIdx + 1 < rows.size() && rows.get(resultsIdx + 1) instanceof List<?> docs) {
            for (Object doc : docs) {
                String id = extractId(doc);
                if (id != null) {
                    keys.add(id);
                }
            }
            return keys;
        }
        // 否则按 RESP2 扁平数组解析：rows[0]=总数，随后交替 [key, fields...]
        for (int i = 1; i < rows.size(); i++) {
            String s = asString(rows.get(i));
            if (s != null && !(rows.get(i) instanceof List<?>)) {
                keys.add(s);
            }
        }
        return keys;
    }

    /** 把 byte[]/String 元素转成 String；其他类型返回 null。 */
    private static String asString(Object el) {
        if (el instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return el instanceof String s ? s : null;
    }

    /** 从 RESP3 单条 doc（扁平 map）里取 {@code id} 字段的值。 */
    private static String extractId(Object doc) {
        if (!(doc instanceof List<?> d)) {
            return null;
        }
        for (int i = 0; i < d.size() - 1; i++) {
            if ("id".equals(asString(d.get(i)))) {
                return asString(d.get(i + 1));
            }
        }
        return null;
    }

    /** 读 hash 中的 FeedItemView（payload JSON）。 */
    public FeedItemView readView(String fullKey) {
        try {
            String payload = (String) redis.opsForHash().get(fullKey, PAYLOAD_FIELD);
            if (payload == null) {
                return null;
            }
            return objectMapper.readValue(payload, FeedItemView.class);
        } catch (Exception e) {
            log.warn("向量 hash 读取失败: key={}, {}", fullKey, e.getMessage());
            return null;
        }
    }
}
