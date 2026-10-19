package com.turbofeed.counter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.turbofeed.counter.bucket.CounterBufferService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.counter.redis.RedisCounterStore;
import com.turbofeed.shared.model.CounterView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 三级读缓存（计数查询出口）。
 *
 * <p>层级：<b>L1 Caffeine</b>（进程内，TTL 60s，兜底即时读）→
 * <b>L2 Redis</b>（{@link RedisCounterStore}，opt-in，跨实例共享、减轻 DB 读压）→
 * <b>L3 DB</b>（{@code post_counter}，权威持久源）。任一上层命中即返回，未命中下沉并回写上层。</p>
 *
 * <p><b>零回归</b>：Redis L2 默认关（{@code turbofeed.counter.redis.enabled=false}），
 * 此时 {@link RedisCounterStore} Bean 不创建，{@code redisStore} 为 {@code null}，
 * 读路径退化为 L1 + L3，本地无 Redis 也能跑。</p>
 */
@Service
public class CounterReadService {

    private final Cache<String, CounterView> l1;
    private final PostCounterRepository repo;
    private final RedisCounterStore redisStore;

    public CounterReadService(PostCounterRepository repo, ObjectProvider<RedisCounterStore> redisProvider) {
        this.l1 = Caffeine.newBuilder().maximumSize(5000).expireAfterWrite(60, TimeUnit.SECONDS).build();
        this.repo = repo;
        // Redis L2 未启用时为 null，读路径跳过 L2（零回归）。
        this.redisStore = redisProvider.getIfAvailable();
    }

    /** 三级读：L1 → L2 → L3，逐层回写。 */
    public CounterView get(String postId) {
        CounterView v = l1.getIfPresent(postId);
        if (v != null) {
            return v;
        }
        if (redisStore != null) {
            v = redisStore.get(postId);
            if (v != null) {
                l1.put(postId, v);
                return v;
            }
        }
        v = repo.get(postId);
        if (v == null) {
            v = CounterView.zero(postId);
        }
        l1.put(postId, v);
        if (redisStore != null) {
            redisStore.put(v);
        }
        return v;
    }

    /** 增量到达时乐观更新 L1，使同实例内读立即可见（flush 后再由 DB 权威值修正）。 */
    public void applyLiveDelta(String postId, long dLike, long dComment, long dShare) {
        CounterView cur = l1.getIfPresent(postId);
        if (cur != null) {
            l1.put(postId, cur.add(dLike, dComment, dShare));
        }
    }

    /** flush 后以权威值刷新 L1/L2。 */
    public void refresh(String postId, CounterView v) {
        l1.put(postId, v);
        if (redisStore != null) {
            redisStore.put(v);
        }
    }
}
