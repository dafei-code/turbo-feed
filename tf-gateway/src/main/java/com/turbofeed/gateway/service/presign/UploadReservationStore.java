package com.turbofeed.gateway.service.presign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 上传预约的 Redis 存取（预签名直传的中间态）。
 *
 * <p><b>为什么放 Redis 而不是 MySQL</b>：预约是「已签发凭证、尚未落库」的中间态，
 * 天然短命（凭证有效期 + 缓冲即过期），且落库尚未发生、此时并没有 media 行可依附。
 * 放 MySQL 等于为一批可能永远不会被用户传完的字节建持久记录，还要额外写清理任务。</p>
 *
 * <p><b>降级口径</b>：Redis 不可用时读写都只告警、返回空/放行——与既有 {@code UploadIdempotency}
 * 同一取舍：依赖故障不阻断上传主链路，代价是该能力暂时失效。</p>
 *
 * <h3>三个键</h3>
 * <ul>
 *   <li>{@code presign:resv:{postId}} —— 预约本体（JSON），供 complete 阶段取回媒体清单；</li>
 *   <li>{@code presign:req:{userId}:{requestId}} —— 幂等索引，同一幂等键复用同一预约
 *       （避免重复提交签出两批对象名，导致孤儿对象翻倍）；</li>
 *   <li>{@code presign:claim:{postId}} —— 收尾权领取（SETNX），保证重复 complete 只落库一次。</li>
 * </ul>
 */
@Slf4j
@Service
public class UploadReservationStore {

    private static final String RESV_PREFIX = "presign:resv:";
    private static final String REQ_PREFIX = "presign:req:";
    private static final String CLAIM_PREFIX = "presign:claim:";

    /** TTL 缓冲（秒）：在凭证有效期之上留出「传完后通知完成」的余量。 */
    private static final long TTL_BUFFER_SECONDS = 300L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MediaProperties properties;

    public UploadReservationStore(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  MediaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 写入（或刷新）预约。 */
    public void save(UploadReservation reservation) {
        try {
            redisTemplate.opsForValue().set(RESV_PREFIX + reservation.postId(),
                    objectMapper.writeValueAsString(reservation), Duration.ofSeconds(ttlSeconds()));
        } catch (Exception e) {
            log.warn("上传预约写入失败（降级放行）: postId={}, {}", reservation.postId(), e.getMessage());
        }
    }

    /** 读取预约；不存在或已过期返回 {@link Optional#empty()}。 */
    public Optional<UploadReservation> load(String postId) {
        try {
            String json = redisTemplate.opsForValue().get(RESV_PREFIX + postId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, UploadReservation.class));
        } catch (Exception e) {
            log.warn("上传预约读取失败: postId={}, {}", postId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 原子领取收尾权：重复 complete（客户端重试 / 网络重发）只有第一次能领到。
     *
     * <p>领取失败即代表已有一次收尾在跑，调用方按幂等处理（直接返回受理回执），
     * 从而杜绝「同批对象落两次库、发两条事件」。Redis 不可用时返回 true（放行），
     * 宁可承担极端情况下的重复，也不让依赖故障卡住上传。</p>
     */
    public boolean claim(String postId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(CLAIM_PREFIX + postId, "1", Duration.ofSeconds(ttlSeconds())));
        } catch (Exception e) {
            log.warn("收尾权领取失败（Redis 异常，降级放行）: postId={}, {}", postId, e.getMessage());
            return true;
        }
    }

    /** 删除预约本体（收尾成功或补偿清理后调用）。 */
    public void delete(String postId) {
        try {
            redisTemplate.delete(RESV_PREFIX + postId);
        } catch (Exception e) {
            log.warn("上传预约删除失败（依赖 TTL 兜底）: postId={}, {}", postId, e.getMessage());
        }
    }

    /** 记录幂等索引：同一 requestId 复用同一预约。requestId 为空时不记录。 */
    public void saveRequestIndex(String userId, String requestId, String postId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REQ_PREFIX + userId + ":" + requestId, postId,
                    Duration.ofSeconds(ttlSeconds()));
        } catch (Exception e) {
            log.warn("预签幂等索引写入失败: userId={}, requestId={}, {}", userId, requestId, e.getMessage());
        }
    }

    /** 按幂等键查已有预约的 postId；无记录返回空。 */
    public Optional<String> findPostIdByRequest(String userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(REQ_PREFIX + userId + ":" + requestId));
        } catch (Exception e) {
            log.warn("预签幂等索引读取失败: userId={}, requestId={}, {}", userId, requestId, e.getMessage());
            return Optional.empty();
        }
    }

    private long ttlSeconds() {
        return properties.getPresign().getExpirySeconds() + TTL_BUFFER_SECONDS;
    }
}
