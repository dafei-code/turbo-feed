package com.turbofeed.gateway.service.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.query.MediaItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 上传接口幂等去重（基于客户端 X-Request-Id）。
 *
 * <p><b>动机</b>：前端按钮防重复点击是体验层，后端必须兜底——同一 {@code requestId}
 * 短期内（{@code turbofeed.media.idempotent-ttl-seconds}，默认 5s）重复提交直接返回
 * 首次受理结果，避免重复落存储、重复送审、重复占用限流配额。异步化后 requestId
 * 同时充当任务去重键，防止 MQ 里堆积重复任务。</p>
 *
 * <p><b>防越权</b>：幂等键绑定 {@code userId}（{@code idem:upload:{userId}:{requestId}}），
 * 不同用户即使携带相同 requestId 也互不干扰，杜绝「借他人 requestId 探活」类越权。</p>
 *
 * <p><b>实现</b>：{@code SET key PENDING NX EX ttl} 占位——首次提交拿到锁（PROCEED），
 * 真实上传完成后把结果（{@link MediaItem} <b>帖子视图</b>的 JSON）覆盖写回该 key；
 * 窗口内重复提交读到此 key：已是结果对象则去重返回（DUPLICATE），仍是 PENDING
 * （并发首次尚未算完）则拒绝（IN_PROGRESS）。requestId 缺省（前端未传）则 SKIP，不做幂等。</p>
 *
 * <p><b>为什么存「帖子视图」而不是 URL 数组</b>：一帖多图下，重放结果必须能还原出
 * {@code postId} 与 {@code images} 才能让前端渲染出同样的轮播；只存 URL 数组会丢掉帖子身份，
 * 第二次提交拿到的响应与第一次形状不同，前端还得写兼容分支。</p>
 *
 * <p><b>降级</b>：Redis 不可用时以 warn 级别跳过幂等（放行上传），避免依赖故障阻断主链路；
 * 代价是幂等暂时失效（重复提交可能重复受理），可接受的取舍。</p>
 */
@Slf4j
@Service
public class UploadIdempotency {

    private static final String PENDING = "PENDING";
    private static final String KEY_PREFIX = "idem:upload:";
    /** 结果 JSON 的起始字符：对象（帖子视图）。用于区分「已有结果」与「仍是占位 PENDING」。 */
    private static final String RESULT_JSON_PREFIX = "{";

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties properties;
    private final ObjectMapper objectMapper;

    public UploadIdempotency(StringRedisTemplate redisTemplate,
                             MediaProperties properties,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 检查该 requestId 是否为重复提交。
     *
     * @return 见 {@link IdempotencyOutcome.Kind}：SKIP（无 requestId）/ PROCEED（首次，可继续上传）/
     *         DUPLICATE（重复且已有结果，取 {@link IdempotencyOutcome#urls()}）/ IN_PROGRESS（并发处理中）
     */
    public IdempotencyOutcome check(String userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return IdempotencyOutcome.skip();
        }
        String key = KEY_PREFIX + userId + ":" + requestId;
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, PENDING, Duration.ofSeconds(properties.getIdempotentTtlSeconds()));
            if (Boolean.TRUE.equals(acquired)) {
                return IdempotencyOutcome.proceed();
            }
            String existing = redisTemplate.opsForValue().get(key);
            if (existing != null && existing.startsWith(RESULT_JSON_PREFIX)) {
                MediaItem post = objectMapper.readValue(existing, MediaItem.class);
                return IdempotencyOutcome.duplicate(post);
            }
            return IdempotencyOutcome.inProgress();
        } catch (Exception e) {
            log.warn("幂等校验 Redis 异常，降级跳过: userId={}, requestId={}, {}", userId, requestId, e.getMessage());
            return IdempotencyOutcome.skip();
        }
    }

    /** 上传成功后回写幂等结果（重复提交时直接返回同一个帖子视图）。 */
    public void store(String userId, String requestId, MediaItem post) {
        if (requestId == null || requestId.isBlank() || post == null) {
            return;
        }
        String key = KEY_PREFIX + userId + ":" + requestId;
        try {
            String json = objectMapper.writeValueAsString(post);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(properties.getIdempotentTtlSeconds()));
        } catch (Exception e) {
            log.warn("幂等结果写回 Redis 异常，忽略: userId={}, requestId={}, {}", userId, requestId, e.getMessage());
        }
    }

    /** 幂等检查结果。 */
    public record IdempotencyOutcome(Kind kind, MediaItem post) {
        static IdempotencyOutcome skip() {
            return new IdempotencyOutcome(Kind.SKIP, null);
        }

        static IdempotencyOutcome proceed() {
            return new IdempotencyOutcome(Kind.PROCEED, null);
        }

        static IdempotencyOutcome duplicate(MediaItem p) {
            return new IdempotencyOutcome(Kind.DUPLICATE, p);
        }

        static IdempotencyOutcome inProgress() {
            return new IdempotencyOutcome(Kind.IN_PROGRESS, null);
        }

        public boolean isDuplicate() {
            return kind == Kind.DUPLICATE;
        }

        public boolean isInProgress() {
            return kind == Kind.IN_PROGRESS;
        }
    }

    public enum Kind {
        /** 无 requestId，不做幂等 */
        SKIP,
        /** 首次提交，可继续上传 */
        PROCEED,
        /** 窗口内重复提交且已有结果，直接返回首次结果 */
        DUPLICATE,
        /** 并发同 requestId 首次尚未算完 */
        IN_PROGRESS
    }
}
