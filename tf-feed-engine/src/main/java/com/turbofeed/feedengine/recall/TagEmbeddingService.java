package com.turbofeed.feedengine.recall;

import java.util.List;
import java.util.Map;

/**
 * 确定性「标签 → 稠密向量」编码器（抖音式双塔 / 向量召回的<b>本地可运行占位</b>实现）。
 *
 * <p><b>为什么需要它</b>：抖音的多路召回里，向量召回（双塔 / ANN）是「隐式语义相似」的承担者——
 * 用户向量与内容向量落在同一空间，靠余弦相似度近邻把"没显式打同一标签但语义相近"的内容捞上来。
 * 线上应有预训练的双塔模型 + 向量检索引擎（Faiss/ANN）产出这些向量；本服务是<b>零外部依赖</b>的占位：
 * 把 {标签→权重} 哈希进一个固定维稠密向量（带符号哈希抵消碰撞偏置 + L2 归一化），
 * 使得<b>用户侧（兴趣标签权重）与内容侧（内容标签）天然处于同一向量空间</b>，余弦相似度即有意义。</p>
 *
 * <p><b>生产接入缝</b>：真正上线时，把 {@link UserEmbeddingSource} / {@link ItemEmbeddingSource} 的实现
 * 换成「调双塔模型服务 / 读向量KV」即可（接口签名不变），本类退居离线回退或调试用途。
 * 哈希编码与训练向量不是同一个空间——<b>两者不能混用</b>：要么全用模型向量，要么全用本占位编码。</p>
 *
 * <p>无 Lombok：纯静态工具类（Java 17，不依赖注解处理）。</p>
 */
public final class TagEmbeddingService {

    /** 向量维度（占位实现；真实双塔维度由模型决定，如 64/128/256）。 */
    public static final int DIM = 64;

    private TagEmbeddingService() {
    }

    /**
     * 把 {标签→权重} 编码成归一化向量。权重越大，该标签在向量上的投影越强。
     *
     * @param tagWeights 标签→权重（如用户兴趣画像、内容标签集合（权重=1））；{@code null}/空 → 零向量
     */
    public static double[] embed(Map<String, Double> tagWeights) {
        double[] v = new double[DIM];
        if (tagWeights == null || tagWeights.isEmpty()) {
            return v;
        }
        for (Map.Entry<String, Double> e : tagWeights.entrySet()) {
            String tag = e.getKey();
            double w = e.getValue() == null ? 0.0 : e.getValue();
            if (tag == null || w == 0.0) {
                continue;
            }
            int bucket = bucketOf(tag);
            double sign = signOf(tag);
            v[bucket] += sign * w;
        }
        return l2normalize(v);
    }

    /** 把标签集合（等权）编码成归一化向量。 */
    public static double[] embed(List<String> tags) {
        double[] v = new double[DIM];
        if (tags == null || tags.isEmpty()) {
            return v;
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            v[bucketOf(tag)] += signOf(tag);
        }
        return l2normalize(v);
    }

    /**
     * 余弦相似度（已 L2 归一化 → 等于点积）。任一为 {@code null} 或维度不符 → 0.0（不相似）。
     */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 标签 → 桶位（均匀散布到 [0,DIM)）。 */
    private static int bucketOf(String tag) {
        int h = tag.hashCode();
        return Math.floorMod(h, DIM);
    }

    /** 用第二个哈希决定符号位，抵消哈希桶碰撞带来的系统性偏置（避免正负权重相互抵消净零）。 */
    private static double signOf(String tag) {
        int h = tag.hashCode();
        int h2 = Integer.rotateLeft(h, 16) ^ 0x9e3779b9;
        return ((h2 & 1) == 0) ? 1.0 : -1.0;
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
