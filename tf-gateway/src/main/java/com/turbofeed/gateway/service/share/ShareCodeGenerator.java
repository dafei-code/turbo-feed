package com.turbofeed.gateway.service.share;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 短链码生成器：Snowflake → 64 位 Feistel 可逆混淆 → Base62 定长 11 位。
 *
 * <p><b>为什么不能直接 Base62(雪花)</b>：雪花 ID 高位含时间戳、低位是 sequence，编码后仍然递增可枚举，
 * 攻击者能从一个 code 推出相邻短链，遍历全站内容。必须做混淆。</p>
 *
 * <p><b>为什么不做 HMAC 截断</b>：截断是有损映射，会引入碰撞，唯一性就只剩"碰运气"。
 * 这里用 Feistel 网络在 64 位空间做<b>双射</b>置换——混淆后仍是 11 位定长、且可逆推回原 ID，
 * 不丢唯一性（方案 §4.1.2）。碰撞兜底仍保留在存储层（SETNX + uk_code）。</p>
 *
 * <p><b>长度为什么是 11</b>：62^10 ≈ 8.4e17 &lt; 2^63 ≈ 9.2e18 &lt; 62^11 ≈ 5.2e19，
 * 所以任意 64 位雪花 ID 的 Base62 表示最多 11 位，左补零定长即可，无需 cycle-walking。</p>
 *
 * <p><b>密钥</b>：{@code turbofeed.share.code-secret}。生产必须显式配置；默认值只供本地演示，
 * 多实例必须一致（否则 A 生成的码 B 无法解码，但编码/跳转不依赖解码，仅运维追溯受影响）。</p>
 */
@Component
public class ShareCodeGenerator {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int LENGTH = 11;
    private static final int ROUNDS = 8;
    private static final BigInteger SIXTY_TWO = BigInteger.valueOf(62);
    private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    private final SecretKeySpec key;

    public ShareCodeGenerator(
            @Value("${" + FeatureFlagKeys.SHARE_CODE_SECRET + ":turbo-feed-local-share-secret-change-me}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 雪花 ID → 11 位不可枚举短码。 */
    public String encode(long snowflakeId) {
        long permuted = feistel(snowflakeId, false);
        BigInteger v = BigInteger.valueOf(permuted);
        if (v.signum() < 0) {
            v = v.add(TWO_64); // Feistel 输出可能最高位为 1，按无符号 64 位解释
        }
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            BigInteger[] qr = v.divideAndRemainder(SIXTY_TWO);
            out[i] = ALPHABET.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(out);
    }

    /**
     * 短码 → 原雪花 ID（仅运维追溯用；跳转链路不依赖解码）。
     *
     * @throws IllegalArgumentException 码含非法字符或长度不为 11
     */
    public long decode(String code) {
        if (code == null || code.length() != LENGTH) {
            throw new IllegalArgumentException("短码长度必须为 " + LENGTH);
        }
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < LENGTH; i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("短码含非法字符: " + code.charAt(i));
            }
            v = v.multiply(SIXTY_TWO).add(BigInteger.valueOf(digit));
        }
        if (v.compareTo(TWO_63) >= 0) {
            v = v.subtract(TWO_64);
        }
        return feistel(v.longValue(), true);
    }

    /** 平衡 Feistel：32+32 两半、8 轮，解密用逆序轮密钥。 */
    private long feistel(long x, boolean decrypt) {
        int left = (int) (x >>> 32);
        int right = (int) x;
        if (!decrypt) {
            for (int round = 0; round < ROUNDS; round++) {
                int f = roundFunction(right, round);
                int nextLeft = right;
                int nextRight = left ^ f;
                left = nextLeft;
                right = nextRight;
            }
        } else {
            for (int round = ROUNDS - 1; round >= 0; round--) {
                int f = roundFunction(left, round);
                int prevLeft = right ^ f;
                int prevRight = left;
                left = prevLeft;
                right = prevRight;
            }
        }
        return ((long) left << 32) | (right & 0xffffffffL);
    }

    /** 轮函数：HMAC-SHA256(密钥, round:right) 取前 4 字节。 */
    private int roundFunction(int half, int round) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal((round + ":" + half).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 4).getInt();
        } catch (Exception e) {
            throw new IllegalStateException("短码混淆失败", e);
        }
    }
}
