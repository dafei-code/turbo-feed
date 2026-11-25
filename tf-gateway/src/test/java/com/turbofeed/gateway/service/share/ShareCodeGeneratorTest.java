package com.turbofeed.gateway.service.share;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 短码生成器单测：双射、定长、不可枚举。 */
class ShareCodeGeneratorTest {

    private final ShareCodeGenerator generator = new ShareCodeGenerator("test-secret-share-code");

    @Test
    void encodeDecodeRoundTrip() {
        long[] ids = {1L, 2L, 42L, 1_000_000L, System.currentTimeMillis() << 22};
        for (long id : ids) {
            String code = generator.encode(id);
            assertEquals(11, code.length(), "短码必须定长 11 位");
            assertEquals(id, generator.decode(code), "编码解码必须互逆");
        }
    }

    @Test
    void noCollisionForSequentialIds() {
        Set<String> codes = new HashSet<>();
        for (long i = 1; i <= 10_000; i++) {
            assertTrue(codes.add(generator.encode(i)), "连续 ID 不得碰撞");
        }
    }

    @Test
    void notEnumerable() {
        // 裸雪花编码后相邻码会共享长前缀；Feistel 混淆后相邻 ID 的码应完全发散
        String a = generator.encode(1_000_000L);
        String b = generator.encode(1_000_001L);
        assertNotEquals(a, b);
        int commonPrefix = 0;
        while (commonPrefix < a.length() && a.charAt(commonPrefix) == b.charAt(commonPrefix)) {
            commonPrefix++;
        }
        assertTrue(commonPrefix < 4, "相邻 ID 的短码不应有明显共同前缀");
    }

    @Test
    void rejectInvalidCode() {
        assertThrows(IllegalArgumentException.class, () -> generator.decode("short"));
        assertThrows(IllegalArgumentException.class, () -> generator.decode("0000000000!"));
    }
}
