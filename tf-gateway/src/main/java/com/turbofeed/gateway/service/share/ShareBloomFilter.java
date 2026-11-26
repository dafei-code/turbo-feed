package com.turbofeed.gateway.service.share;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 短链防穿透布隆过滤器（方案 §4.8 / P1-3，默认关闭）。
 *
 * <p><b>定位</b>：限流之后的第二道闸门，<b>不是权威数据源</b>。只挡"随手编一个 code"的穿透流量；
 * 拿到真短链狂刷的流量由 L1 本地缓存 + 限流负责。必须先全量回填、再 shadow 7 天观测误杀候选为 0，
 * 才能开 {@code enforce=true}——空 BF 直接开严格模式会把全部老短链误杀成不可用页。</p>
 *
 * <p><b>技术选型</b>：Redis 原生位图（SETBIT/GETBIT）+ Lua 批量位操作，零新依赖、零新连接。
 * 不用 RedisBloom（redis:7-alpine 无该模块）、不用 Redisson（默认不装配且启动即连 Redis）。
 * 单 key 单槽，Cluster 下不触发 CROSSSLOT。</p>
 *
 * <p><b>参数</b>：按 n=1e7 / p=1e-4 一次算定（m≈1.92e8 bit≈23MB，k=13）。m/k 与位图绑定，
 * 变更必须走 key 版本化重建（v1→v2 双写→回填→切流→观察→删 v1）。<b>位图不设 TTL</b>——
 * BF 过期而无回填等于老短链集体误杀。</p>
 *
 * <p><b>删除语义</b>：短链作废<em>不从 BF 删位</em>（位图不支持，Counting BF 要 4 倍内存）。
 * 已删除 code 仍判"可能存在"→继续查 Redis/DB→由状态校验返回不可用页，正确性不受影响。</p>
 *
 * <p><b>失败语义</b>：Redis 异常一律 fail-open（BF 挂了全部短链仍可跳转）；add 失败只打点，
 * 漏加的 code 由回填任务补齐。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = FeatureFlagKeys.SHARE_BLOOM_FILTER_ENABLED, havingValue = "true")
public class ShareBloomFilter {

    /** 全局单个位图；不设 TTL（见类注释）。 */
    static final String KEY = "tf:share:bf";

    private static final long EXPECTED = 10_000_000L;
    private static final double FPP = 0.0001;
    /** m = -n·ln(p)/(ln2)² ≈ 1.92e8 bit */
    static final long BIT_SIZE = (long) Math.ceil(-EXPECTED * Math.log(FPP) / (Math.log(2) * Math.log(2)));
    /** k = (m/n)·ln2 ≈ 13 */
    static final int HASH_COUNT = (int) Math.round((double) BIT_SIZE / EXPECTED * Math.log(2));

    /** 批量置位：对 ARGV 中每个 bit 位置 SETBIT 1。单 key，Cluster 安全。 */
    private static final RedisScript<Long> SET_BITS = new DefaultRedisScript<>(
            "for i,p in ipairs(ARGV) do redis.call('SETBIT', KEYS[1], p, 1) end return 1", Long.class);
    /** 批量校验：任一 bit 为 0 即返回 0（确定不存在），全 1 返回 1（可能存在）。 */
    private static final RedisScript<Long> GET_BITS = new DefaultRedisScript<>(
            "for i,p in ipairs(ARGV) do if redis.call('GETBIT', KEYS[1], p) == 0 then return 0 end end return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final boolean enforce;
    private final Counter denied;
    private final Counter passed;
    private final Counter error;
    private final Counter addFailed;

    public ShareBloomFilter(StringRedisTemplate redis, MeterRegistry registry,
                            @Value("${" + FeatureFlagKeys.SHARE_BLOOM_FILTER_ENFORCE + ":false}") boolean enforce) {
        this.redis = redis;
        this.enforce = enforce;
        this.denied = Counter.builder("tf_share_bf_total").tag("result", "denied")
                .description("短链 BF 判定确定不存在").register(registry);
        this.passed = Counter.builder("tf_share_bf_total").tag("result", "passed")
                .description("短链 BF 判定可能存在").register(registry);
        this.error = Counter.builder("tf_share_bf_total").tag("result", "error")
                .description("短链 BF 查询失败（fail-open）").register(registry);
        this.addFailed = Counter.builder("tf_share_bf_add_failed_total")
                .description("短链 BF 置位失败（靠回填补齐）").register(registry);
        log.info("ShareBloomFilter 已启用: bitSize={}, hashCount={}, enforce={}", BIT_SIZE, HASH_COUNT, enforce);
    }

    /** 生成短链成功后调用；失败不回滚主流程，漏加靠回填任务补齐。 */
    public void add(String code) {
        try {
            redis.execute(SET_BITS, Collections.singletonList(KEY), (Object[]) positions(code));
        } catch (Exception e) {
            addFailed.increment();
            log.warn("短链 BF 置位失败（fail-open，靠回填补齐）: code={}, {}", code, e.toString());
        }
    }

    /**
     * 判定是否可拒绝。返回 true = 确定不存在，可拒绝；false = 可能存在或降级放行。
     * 判定点放在查 Redis/DB 之前，BF 报"不存在"时一次后端都不碰。
     */
    public boolean shouldReject(String code) {
        try {
            Long r = redis.execute(GET_BITS, Collections.singletonList(KEY), (Object[]) positions(code));
            if (r != null && r == 1L) {
                passed.increment();
                return false;
            }
            denied.increment();
            return enforce; // shadow 模式只观测不拦截
        } catch (RedisConnectionFailureException e) {
            error.increment();
            log.warn("短链 BF 查询失败，fail-open: code={}, {}", code, e.toString());
            return false;
        } catch (Exception e) {
            error.increment();
            log.warn("短链 BF 查询异常，fail-open: code={}, {}", code, e.toString());
            return false;
        }
    }

    /** Kirsch-Mitzenmacher 双哈希：h_i = h1 + i*h2 (mod m)，只需算两次哈希。 */
    static String[] positions(String code) {
        long h1 = fnv1a64(code, 0xcbf29ce484222325L);
        long h2 = fnv1a64(code, 0x9e3779b97f4a7c15L);
        if (h2 == 0) {
            h2 = 0x9e3779b97f4a7c15L;
        }
        String[] out = new String[HASH_COUNT];
        for (int i = 0; i < HASH_COUNT; i++) {
            out[i] = Long.toString(Math.floorMod(h1 + (long) i * h2, BIT_SIZE));
        }
        return out;
    }

    private static long fnv1a64(String s, long seed) {
        long h = seed;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
