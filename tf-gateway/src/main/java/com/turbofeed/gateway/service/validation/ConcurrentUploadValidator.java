package com.turbofeed.gateway.service.validation;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 链环 1（Order 1）：同用户上传并发护栏——同一用户同一时刻仅允许一个上传任务进行中。
 *
 * <p>实现为 <b>SET NX EX 原子占位</b>（{@code SET key 1 NX EX ttl}）：检查与占位在
 * Redis 单命令内原子完成，杜绝「先 hasKey 再判断」的 TOCTOU 竞态（两个并发请求
 * 同时通过 hasKey 检查）。占位 key：{@code rl:upload:inflight:{userId}}
 * （前缀 rl 即 rate-limit，与媒体存储 key 前缀 media 区分）。</p>
 *
 * <p>三重释放保障：</p>
 * <ol>
 *   <li><b>正常释放</b>——占位成功即注册完成回调删除 key，由
 *       {@code MediaUploadService#upload} 的 finally 触发（校验失败 / 业务异常 / 正常
 *       返回均覆盖）；</li>
 *   <li><b>TTL 兜底</b>——进程崩溃 / Redis 删除失败时，{@code inflight-ttl-seconds}
 *       （默认 30s）到期自动过期，用户不会永久锁死；</li>
 *   <li><b>占位者独占删除权</b>——只有 NX 占位成功的一方能注册删除回调，
 *       不存在误删他人占位的窗口。</li>
 * </ol>
 *
 * <p><b>fail-open 决策</b>：Redis 连接失败时放行（warn 日志）而非拒绝上传——本环节
 * 是防护性增强而非正确性依赖，Redis 故障不应阻断上传主链路（可用性优先）。
 * 若业务升级为强护栏（如付费配额），应改为 fail-closed 并抛
 * {@code DEPENDENCY_UNAVAILABLE}。</p>
 */
@Component
@Order(1)
public class ConcurrentUploadValidator extends UploadValidator {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUploadValidator.class);

    /** 占位 key 前缀（rl = rate-limit）。 */
    static final String INFLIGHT_KEY_PREFIX = "rl:upload:inflight:";

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties properties;

    public ConcurrentUploadValidator(StringRedisTemplate redisTemplate, MediaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    protected void doValidate(UploadValidation context) {
        String key = INFLIGHT_KEY_PREFIX + context.userId();
        Duration ttl = Duration.ofSeconds(properties.getRateLimit().getInflightTtlSeconds());
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            if (!Boolean.TRUE.equals(acquired)) {
//                “快速失败”策略，快速反馈用户，避免无谓等待。
                throw new BizException(ErrorCode.UPLOAD_IN_PROGRESS, "已有上传任务进行中，请等待完成后再试");
            }
        } catch (RedisConnectionFailureException e) {
//            Redis 不可用时的降级处理（容灾设计）
            log.warn("Redis 不可用，并发护栏降级放行（fail-open）: userId={}", context.userId(), e);
            return;
        }
        context.onCompletion(() -> {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                // 释放失败不影响响应；TTL 兜底过期，用户最多等待 inflight-ttl-seconds
                log.warn("上传占位释放失败（依赖 TTL 兜底过期）: key={}", key, e);
            }
        });
    }

    /**
     * 直传路径（{@code presign}）显式获取并发占位。
     *
     * <p>与 {@link #doValidate} 共用同一把「同用户同时仅一个上传任务」的锁
     * （key = {@code rl:upload:inflight:{userId}}），使并发护栏在 {@code upload} 与
     * {@code presign}/{@code complete} 两条入口语义一致——否则被封/刷接口用户可走直传并发多发，
     * 绕过 {@code upload} 路径才有的占位锁。{@code SET NX EX} 原子占位，Redis 不可用 fail-open。</p>
     *
     * @return true 占位成功（可继续签发凭证）；false 已有任务进行中
     */
    public boolean tryAcquireInflight(String userId) {
        String key = INFLIGHT_KEY_PREFIX + userId;
        Duration ttl = Duration.ofSeconds(properties.getRateLimit().getInflightTtlSeconds());
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
        } catch (RedisConnectionFailureException e) {
            // 与 doValidate 一致的容灾：Redis 故障不阻断上传主链路（可用性优先）
            log.warn("Redis 不可用，并发护栏降级放行（fail-open）: userId={}", userId, e);
            return true;
        }
    }

    /**
     * 直传路径（{@code complete}）释放并发占位。
     *
     * <p>直传任务跨度 = presign → 客户端 PUT → complete，占位锁须在 complete 收尾时释放
     * （而非 presign 返回时，否则锁立即失效）。key 由 userId 决定、幂等删除，
     * 不存在也安全；TTL 仍兜底进程崩溃场景。</p>
     *
     * @param userId 归属用户（与获取时同一 userId）
     */
    public void releaseInflight(String userId) {
        String key = INFLIGHT_KEY_PREFIX + userId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            // 释放失败不影响响应；TTL 兜底过期，用户最多等待 inflight-ttl-seconds
            log.warn("上传占位释放失败（依赖 TTL 兜底过期）: key={}", key, e);
        }
    }
}
