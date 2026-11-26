package com.turbofeed.gateway.service.share;

import com.turbofeed.gateway.repository.ShareRepository;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 短链 BF 全量回填（方案 §4.8.4 步骤①，仅 BF 开启时注册）。
 *
 * <p><b>为什么必须有</b>：BF 无假阴性只对"加入过的元素"成立。已有短链没回填就开严格模式，
 * 会把老短链全部误杀成不可用页。因此 "BF 开启 → 先回填 → 再 shadow 7 天 → 最后 enforce" 三步缺一不可。</p>
 *
 * <p><b>幂等</b>：SETBIT 天然幂等，多实例重复跑无害；用 {@code tf:share:bf:backfill:done} 标记
 * 避免每次启动重复扫描（标记 365 天过期，作为重建后的再回填兜底）。</p>
 *
 * <p><b>不让启动卡死</b>：分页扫全分片，批间 sleep 让出 DB；Redis 不可用时 add 自身 fail-open，
 * 回填标记写不进去也只是下次重启重扫，不影响网关启动。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = FeatureFlagKeys.SHARE_BLOOM_FILTER_ENABLED, havingValue = "true")
public class ShareBloomBackfillRunner implements ApplicationRunner {

    private static final String DONE_KEY = "tf:share:bf:backfill:done";
    private static final int PAGE_SIZE = 1000;

    private final ShareRepository shareRepository;
    private final ShareBloomFilter bloomFilter;
    private final StringRedisTemplate redis;
    private final MeterRegistry registry;

    @Value("${turbofeed.share.bf-backfill-batch-sleep-ms:20}")
    private long batchSleepMs;

    public ShareBloomBackfillRunner(ShareRepository shareRepository, ShareBloomFilter bloomFilter,
                                    StringRedisTemplate redis, MeterRegistry registry) {
        this.shareRepository = shareRepository;
        this.bloomFilter = bloomFilter;
        this.redis = redis;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Boolean done = redis.hasKey(DONE_KEY);
            if (Boolean.TRUE.equals(done)) {
                log.info("短链 BF 已回填过，跳过（如需重建请删 {}）", DONE_KEY);
                return;
            }
        } catch (Exception e) {
            log.warn("短链 BF 回填标记读取失败，本次仍执行回填: {}", e.toString());
        }

        Timer.Sample sample = Timer.start(registry);
        String cursor = "";
        int scanned = 0;
        int batches = 0;
        try {
            while (true) {
                java.util.List<String> codes = shareRepository.scanCodesAfter(cursor, PAGE_SIZE);
                if (codes.isEmpty()) {
                    break;
                }
                for (String code : codes) {
                    bloomFilter.add(code);
                }
                scanned += codes.size();
                cursor = codes.get(codes.size() - 1);
                batches++;
                if (batchSleepMs > 0) {
                    Thread.sleep(batchSleepMs); // 批间让出 DB，避免启动期冲击
                }
            }
            try {
                redis.opsForValue().set(DONE_KEY, "1", Duration.ofDays(365));
            } catch (Exception e) {
                log.warn("短链 BF 回填标记写入失败（下次重启会重扫，幂等无害）: {}", e.toString());
            }
            log.info("短链 BF 回填完成: codes={}, batches={}", scanned, batches);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("短链 BF 回填被中断: codes={}", scanned);
        } catch (Exception e) {
            // 回填是优化项，失败不能否决启动；漏回填的 code 靠下次重启或运维补跑
            log.warn("短链 BF 回填失败（fail-open，不影响网关启动）: codes={}, {}", scanned, e.toString());
        } finally {
            sample.stop(Timer.builder("tf_share_bf_backfill_duration_seconds")
                    .description("短链 BF 全量回填耗时").register(registry));
        }
    }
}
