package com.turbofeed.gateway.training;

import com.turbofeed.gateway.repository.InteractionEventRepository;
import com.turbofeed.gateway.repository.InteractionEventRow;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * A1 双塔用户向量训练作业的行为验证（纯 Mockito 单测，不依赖 MySQL/Redis 实例）。
 *
 * <p>验证 {@link TwoTowerUserTrainer} 的核心契约：从 interaction_event 读样本 → 读对应
 * {@code tf:emb:item:} 向量 → 按「行为权重 × recency 衰减」加权池化 → L2 归一 → 写
 * {@code tf:emb:user:}。同时覆盖「开关关闭时不写 KV」。</p>
 */
class TwoTowerUserTrainerTest {

    private static final int DIM = 4;
    private static final String ITEM_PREFIX = "tf:emb:item:";
    private static final String USER_PREFIX = "tf:emb:user:";

    private static double[] v(double... xs) {
        return xs;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private TwoTowerUserTrainer trainer(InteractionEventRepository repo, StringRedisTemplate redis, boolean enabled) {
        return new TwoTowerUserTrainer(repo, redis, enabled, ITEM_PREFIX, USER_PREFIX,
                DIM, 7, 200_000, 7.0, 100_000);
    }

    @Test
    void poolsItemVectorsAndWritesUserVector() {
        long userId = 1000000000000000000L;
        Instant now = Instant.now();
        double[] itemVec1 = v(1, 0, 0, 0); // WATCH, 1 天前
        double[] itemVec2 = v(0, 1, 0, 0); // LIKE, 3 天前

        InteractionEventRow r1 = new InteractionEventRow(userId, "post/A/1", "WATCH",
                null, null, null, null, null, now.minus(Duration.ofDays(1)));
        InteractionEventRow r2 = new InteractionEventRow(userId, "post/A/2", "LIKE",
                null, null, null, null, null, now.minus(Duration.ofDays(3)));

        InteractionEventRepository repo = mock(InteractionEventRepository.class);
        when(repo.listSince(any(Instant.class), anyInt())).thenReturn(List.of(r1, r2));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(ITEM_PREFIX + "post/A/1")).thenReturn(EmbeddingCodec.encode(itemVec1));
        when(ops.get(ITEM_PREFIX + "post/A/2")).thenReturn(EmbeddingCodec.encode(itemVec2));

        TwoTowerUserTrainer trainer = trainer(repo, redis, true);
        int wrote = trainer.train();

        assertEquals(1, wrote, "单个用户应写入 1 条用户向量");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        verify(ops).set(keyCap.capture(), valCap.capture());
        assertEquals(USER_PREFIX + userId, keyCap.getValue());

        double[] out = EmbeddingCodec.decode(valCap.getValue());
        assertTrue(out != null && out.length == DIM, "写出向量维度应一致");
        assertEquals(1.0, norm(out), 1e-9, "用户向量应 L2 归一为单位向量");

        // 复算期望的未归一池化向量，验证方向对齐（行为权重 × recency 衰减）
        double w1 = 0.4 * Math.pow(0.5, 1.0 / 7.0); // WATCH=0.4, 1 天
        double w2 = 1.0 * Math.pow(0.5, 3.0 / 7.0); // LIKE=1.0, 3 天
        double[] expected = v(w1 * 1, w2 * 1, 0, 0);
        // out = expected / |expected| → dot(out, expected) == |expected|
        assertEquals(norm(expected), dot(out, expected), 1e-9,
                "用户向量方向应与加权池化结果一致");
    }

    @Test
    void disabledWritesNothing() {
        InteractionEventRepository repo = mock(InteractionEventRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        TwoTowerUserTrainer trainer = trainer(repo, redis, false);
        int wrote = trainer.train();

        assertEquals(0, wrote);
        verify(ops, never()).set(anyString(), anyString());
        verify(repo, never()).listSince(any(Instant.class), anyInt());
    }

    @Test
    void skipsUsersWithNoUsableItemVector() {
        long userId = 2000000000000000000L;
        Instant now = Instant.now();
        InteractionEventRow r = new InteractionEventRow(userId, "post/B/1", "IMPRESSION",
                null, null, null, null, null, now.minus(Duration.ofDays(1)));

        InteractionEventRepository repo = mock(InteractionEventRepository.class);
        when(repo.listSince(any(Instant.class), anyInt())).thenReturn(List.of(r));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        TwoTowerUserTrainer trainer = trainer(repo, redis, true);
        int wrote = trainer.train();

        assertEquals(0, wrote, "纯曝光（权重 0）且无可用 item 向量 → 跳过");
        verify(ops, never()).set(anyString(), anyString());
    }
}
