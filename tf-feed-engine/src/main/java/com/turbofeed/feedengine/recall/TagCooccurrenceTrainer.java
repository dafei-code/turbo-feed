package com.turbofeed.feedengine.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标签共现向量训练器（A0 轻量「学出来的」embedding，无需 ML 基础设施）。
 *
 * <p><b>为什么是它</b>：抖音式向量召回要"真·语义相似"的向量，而非 {@link TagEmbeddingService} 的
 * 确定性哈希。A0 阶段没有曝光/点击交互日志，但时间线里每件内容都带标签——同一内容里共现的标签
 * 天然语义相近。本训练器用 <b>Skip-Gram Negative Sampling（SGNS）</b> 在「标签共现」上训出
 * {@code dim} 维 tag 向量：共现越多的标签向量越近（如 basketball 与 nba）。</p>
 *
 * <p><b>产出</b>：
 * <ul>
 *   <li>item 向量 = 其内容标签向量的均值（L2 归一），写 KV {@code tf:emb:item:{timelineKey}}</li>
 *   <li>tag 向量 = SGNS 结果，写 KV {@code tf:emb:tag:{tag}}，并保留内存副本供用户向量即时聚合</li>
 * </ul>
 * 召回通道只"读 KV + 余弦"，模型/训练全在 Java 侧离线/近线完成，经 Redis 向量 KV 与引擎解耦。</p>
 *
 * <p><b>触发</b>：仅 {@code turbofeed.feed.recall.vector.mode=model} 且 {@code auto-train=true} 时，
 * 在 {@code ContextRefreshedEvent} 扫描时间线训练并写 KV。任何异常 fail-open（warn 后退回规则召回，
 * 绝不阻断启动/浏览）。训练幂等（AtomicBoolean 防重复）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Component
public class TagCooccurrenceTrainer {

    private static final Logger log = LoggerFactory.getLogger(TagCooccurrenceTrainer.class);
    private static final String TL_PREFIX = "tf:feed:tl:";
    private static final int MAX_POOL = 3;
    private static final int MERGE_BUCKETS = 3;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long TRAIN_SEED = 20261010L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String mode;
    private final boolean autoTrain;
    private final int dim;
    private final String itemPrefix;
    private final String tagPrefix;
    private final int epochs;
    private final double lr;
    private final int negatives;
    private final int maxScan;
    private final AtomicBoolean trained = new AtomicBoolean(false);

    /** 运行时内存中的 tag 向量（训练后填充；供 {@link RedisUserEmbeddingSource} 即时聚合用户向量）。 */
    private volatile Map<String, double[]> tagVectors = new HashMap<>();

    public TagCooccurrenceTrainer(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  @Value("${turbofeed.feed.recall.vector.mode:hash}") String mode,
                                  @Value("${turbofeed.feed.recall.vector.auto-train:true}") boolean autoTrain,
                                  @Value("${turbofeed.feed.recall.vector.dim:64}") int dim,
                                  @Value("${turbofeed.feed.recall.vector.item-prefix:tf:emb:item:}") String itemPrefix,
                                  @Value("${turbofeed.feed.recall.vector.tag-prefix:tf:emb:tag:}") String tagPrefix,
                                  @Value("${turbofeed.feed.recall.vector.epochs:2}") int epochs,
                                  @Value("${turbofeed.feed.recall.vector.learning-rate:0.025}") double lr,
                                  @Value("${turbofeed.feed.recall.vector.negatives:5}") int negatives,
                                  @Value("${turbofeed.feed.recall.vector.max-scan:5000}") int maxScan) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.mode = mode;
        this.autoTrain = autoTrain;
        this.dim = dim;
        this.itemPrefix = itemPrefix;
        this.tagPrefix = tagPrefix;
        this.epochs = epochs;
        this.lr = lr;
        this.negatives = negatives;
        this.maxScan = maxScan;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onStart() {
        if (!"model".equalsIgnoreCase(mode) || !autoTrain) {
            return;
        }
        if (trained.compareAndSet(false, true)) {
            try {
                trainAndPopulate();
            } catch (Exception e) {
                log.warn("向量共现训练失败（fail-open，退回规则召回）: {}", e.getMessage());
                trained.set(false);
            }
        }
    }

    /** 训练并写 KV（幂等；可重复调用，如 e2e 探针在 seed 后触发重训）。 */
    public synchronized void trainAndPopulate() {
        List<FeedItemView> items = scanItems();
        if (items.isEmpty()) {
            log.info("向量共现训练：无可用内容，跳过");
            return;
        }
        List<List<String>> tagLists = new ArrayList<>(items.size());
        for (FeedItemView it : items) {
            tagLists.add(new ArrayList<>(new LinkedHashSet<>(it.tags())));
        }
        Map<String, double[]> vecs = trainSgns(tagLists);
        this.tagVectors = vecs;
        int tagWritten = 0;
        for (Map.Entry<String, double[]> e : vecs.entrySet()) {
            try {
                redis.opsForValue().set(tagPrefix + e.getKey(), EmbeddingCodec.encode(e.getValue()));
                tagWritten++;
            } catch (Exception ignore) {
                // KV 写失败单条跳过，训练结果仍在内存供用户向量使用
            }
        }
        int itemWritten = 0;
        for (FeedItemView it : items) {
            double[] iv = itemVector(it.tags());
            if (iv == null) {
                continue;
            }
            try {
                redis.opsForValue().set(itemPrefix + it.timelineKey(), EmbeddingCodec.encode(iv));
                itemWritten++;
            } catch (Exception ignore) {
                // 单条跳过
            }
        }
        log.info("向量共现训练完成：tags={}, itemsScanned={}, tagKV={}, itemKV={}",
                vecs.size(), items.size(), tagWritten, itemWritten);
    }

    /** 用户向量：兴趣标签权重加权聚合 tag 向量后 L2 归一（无向量/空画像 → null）。 */
    public double[] userVector(Map<String, Double> tagWeights) {
        if (tagWeights == null || tagWeights.isEmpty() || tagVectors.isEmpty()) {
            return null;
        }
        double[] acc = new double[dim];
        double norm = 0.0;
        for (Map.Entry<String, Double> e : tagWeights.entrySet()) {
            double[] v = tagVectors.get(e.getKey());
            if (v == null) {
                continue;
            }
            double w = e.getValue() == null ? 0.0 : e.getValue();
            for (int i = 0; i < dim; i++) {
                acc[i] += w * v[i];
            }
            norm += Math.abs(w);
        }
        if (norm == 0.0) {
            return null;
        }
        return l2normalize(acc);
    }

    /** 内容向量：标签向量均值后 L2 归一（无向量 → null）。 */
    public double[] itemVector(List<String> tags) {
        if (tags == null || tags.isEmpty() || tagVectors.isEmpty()) {
            return null;
        }
        double[] acc = new double[dim];
        int hit = 0;
        for (String t : tags) {
            double[] v = tagVectors.get(t);
            if (v == null) {
                continue;
            }
            for (int i = 0; i < dim; i++) {
                acc[i] += v[i];
            }
            hit++;
        }
        if (hit == 0) {
            return null;
        }
        return l2normalize(acc);
    }

    public boolean hasVectors() {
        return !tagVectors.isEmpty();
    }

    // ---- 内部 ----

    private List<FeedItemView> scanItems() {
        List<FeedItemView> out = new ArrayList<>();
        try {
            LocalDate today = LocalDate.now();
            int scanned = 0;
            for (int pool = 1; pool <= MAX_POOL && scanned < maxScan; pool++) {
                for (int d = 0; d < MERGE_BUCKETS && scanned < maxScan; d++) {
                    String date = today.minusDays(d).format(DATE_FMT);
                    String key = TL_PREFIX + pool + ":" + date;
                    Set<String> members = redis.opsForZSet().range(key, 0, -1);
                    if (members == null) {
                        continue;
                    }
                    for (String json : members) {
                        if (scanned >= maxScan) {
                            break;
                        }
                        FeedItemView it = parse(json);
                        if (it == null || it.tags() == null || it.tags().isEmpty()) {
                            continue;
                        }
                        out.add(it);
                        scanned++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("扫描时间线失败（向量训练可能不全）: {}", e.getMessage());
        }
        return out;
    }

    private FeedItemView parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FeedItemView.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Skip-Gram Negative Sampling：在标签共现上训 dim 维向量。 */
    private Map<String, double[]> trainSgns(List<List<String>> tagLists) {
        Map<String, Integer> freq = new HashMap<>();
        List<Set<String>> pairs = new ArrayList<>(tagLists.size());
        for (List<String> tags : tagLists) {
            Set<String> s = new LinkedHashSet<>(tags);
            if (s.size() < 1) {
                continue;
            }
            pairs.add(s);
            for (String t : s) {
                freq.merge(t, 1, Integer::sum);
            }
        }
        List<String> vocab = new ArrayList<>(freq.keySet());
        if (vocab.isEmpty()) {
            return new HashMap<>();
        }
        Random rnd = new Random(TRAIN_SEED);
        Map<String, double[]> vec = new HashMap<>(vocab.size());
        for (String t : vocab) {
            double[] v = new double[dim];
            for (int i = 0; i < dim; i++) {
                v[i] = (rnd.nextDouble() * 2.0 - 1.0) * 0.1;
            }
            vec.put(t, v);
        }
        // 负采样表（unigram^0.75 累计分布）
        double[] pow = new double[vocab.size()];
        double total = 0.0;
        for (int i = 0; i < vocab.size(); i++) {
            double f = Math.pow(freq.get(vocab.get(i)), 0.75);
            pow[i] = f;
            total += f;
        }
        double[] cum = new double[vocab.size()];
        for (int i = 0; i < vocab.size(); i++) {
            cum[i] = (i > 0 ? cum[i - 1] : 0.0) + pow[i];
        }
        for (int ep = 0; ep < epochs; ep++) {
            for (Set<String> s : pairs) {
                for (String center : s) {
                    double[] cv = vec.get(center);
                    for (String co : s) {
                        if (co.equals(center)) {
                            continue;
                        }
                        sgdStep(cv, vec.get(co), 1.0, rnd);
                        for (int n = 0; n < negatives; n++) {
                            String neg = sampleNeg(vocab, cum, total, rnd);
                            if (neg.equals(center)) {
                                continue;
                            }
                            sgdStep(cv, vec.get(neg), 0.0, rnd);
                        }
                    }
                }
            }
        }
        for (double[] v : vec.values()) {
            l2normalize(v);
        }
        return vec;
    }

    private void sgdStep(double[] target, double[] other, double label, Random rnd) {
        double dot = dot(target, other);
        double sig = sigmoid(dot);
        double g = lr * (label - sig);
        for (int i = 0; i < dim; i++) {
            double tv = target[i];
            target[i] += g * other[i];
            other[i] += g * tv;
        }
    }

    private String sampleNeg(List<String> vocab, double[] cum, double total, Random rnd) {
        double target = rnd.nextDouble() * total;
        int lo = 0;
        int hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return vocab.get(lo);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double sigmoid(double x) {
        if (x >= 0.0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(x);
        return z / (1.0 + z);
    }

    private static double[] l2normalize(double[] v) {
        double s = 0.0;
        for (double x : v) {
            s += x * x;
        }
        if (s == 0.0) {
            return v;
        }
        double n = Math.sqrt(s);
        for (int i = 0; i < v.length; i++) {
            v[i] /= n;
        }
        return v;
    }
}
