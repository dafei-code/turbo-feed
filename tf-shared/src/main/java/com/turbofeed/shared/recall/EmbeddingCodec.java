package com.turbofeed.shared.recall;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 稠密向量 ↔ 字符串 编解码（存 Redis KV 用）。
 *
 * <p>向量召回的 item/user 向量统一以「int dim + dim 个 double」的定长二进制，
 * base64 后存为 Redis 字符串值（与 {@code StringRedisTemplate} 兼容，无需二进制模板）。
 * 解码失败返回 {@code null}（调用方据此 fail-open 跳过该内容）。</p>
 *
 * <p>与具体模型无关：哈希占位、SGNS 共现、真实双塔产出的向量都走同一套编解码，
 * 切换来源只换 Source 实现，本类与余弦计算不动。</p>
 *
 * <p>原位于 tf-feed-engine/recall，因 A1 离线双塔训练作业（gateway 模块）也需同一套
 * 编解码而抽到 {@code tf-shared}，成为引擎与网关共用的唯一向量编解码实现，避免重复。</p>
 */
public final class EmbeddingCodec {

    private static final int HEADER_BYTES = Integer.BYTES;

    private EmbeddingCodec() {
    }

    /** 向量 → base64 字符串（{@code null} → {@code null}）。 */
    public static String encode(double[] v) {
        if (v == null) {
            return null;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(HEADER_BYTES + v.length * Double.BYTES);
        buf.putInt(v.length);
        for (double x : v) {
            buf.putDouble(x);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** base64 字符串 → 向量（非法/维度不符 → {@code null}，fail-open）。 */
    public static double[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            byte[] b = Base64.getDecoder().decode(s);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b);
            int dim = buf.getInt();
            if (dim <= 0 || b.length != HEADER_BYTES + dim * Double.BYTES) {
                return null;
            }
            double[] v = new double[dim];
            for (int i = 0; i < dim; i++) {
                v[i] = buf.getDouble();
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向量 → 原始 FLOAT32 小端字节（供 RediSearch HNSW 的 VECTOR 字段存储）。
     *
     * <p>RediSearch 的向量索引只认<b>原始 FLOAT32 二进制</b>（不接受 base64、也不接受 double），
     * 且要求小端序；{@code StringRedisTemplate} 按 UTF-8 序列化会破坏二进制，故 HNSW 路径必须
     * 经 {@code RedisConnection} 原生命令直接写字节。double→float 精度损失可忽略，L2 归一不变。</p>
     *
     * <p>与原 double/base64 编解码并存：KV 走 base64（{@link #encode}），向量索引走本方法。</p>
     */
    public static byte[] toFloat32Bytes(double[] v) {
        if (v == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : v) {
            buf.putFloat((float) x);
        }
        return buf.array();
    }

    /** 原始 FLOAT32 小端字节 → 向量（非法/长度不符 → {@code null}，fail-open）。 */
    public static double[] fromFloat32Bytes(byte[] b) {
        if (b == null || b.length == 0 || b.length % Float.BYTES != 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        double[] v = new double[b.length / Float.BYTES];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
