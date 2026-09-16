package com.turbofeed.gateway.service.presign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 待落库对象登记表（ZSET）：member=mediaId，score=「预约过期时间戳(ms)」。
     *
     * <p><b>孤儿问题的由来</b>：客户端直传后若不调用 complete（放弃上传 / 断网 / 进程崩溃），
     * 对象已落在对象存储里但<b>没有任何 DB 行引用它</b>——既不可见也删不掉，只能靠清理任务回收。
     * 本集合就是这份「已签发、尚未落库」的待清理名单：正常完成时移除，
     * 逾期仍存在即判定为孤儿。</p>
     *
     * <p>与预约本体（有 TTL）不同，本集合<b>不设 TTL</b>——它本身就是清理任务的输入源，
     * 若随 TTL 消失，过期项反而永远查不到。条目由清理任务或正常收尾主动移除。</p>
     */
    private static final String PENDING_ZSET = "presign:pending";

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
        // 同步登记「待落库」：逾期未释放即由清理任务按孤儿回收
        markPending(reservation.slots().stream().map(UploadReservation.Slot::mediaId).toList(),
                System.currentTimeMillis() + ttlSeconds() * 1000L);
    }

    /**
     * 登记待落库对象。
     *
     * @param mediaIds        本批对象名
     * @param expireAtMillis 判定为孤儿的时刻（= 预约过期时刻；早于此不算孤儿）
     */
    public void markPending(List<String> mediaIds, long expireAtMillis) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return;
        }
        try {
            for (String mediaId : mediaIds) {
                redisTemplate.opsForZSet().add(PENDING_ZSET, mediaId, expireAtMillis);
            }
        } catch (Exception e) {
            log.warn("待落库登记失败（可能残留孤儿）: n={}, {}", mediaIds.size(), e.getMessage());
        }
    }

    /**
     * 释放待落库对象（收尾成功或已补偿清理时调用）——从孤儿名单中移除，避免被误删。
     */
    public void releasePending(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(PENDING_ZSET, mediaIds.toArray());
        } catch (Exception e) {
            log.warn("待落库释放失败（对象可能被误判为孤儿）: n={}, {}", mediaIds.size(), e.getMessage());
        }
    }

    /**
     * 取出「已过期且仍未被释放」的对象（孤儿候选），并以 ZREM 的返回值认领。
     *
     * <p><b>为什么用 ZREM 认领</b>：多实例下两个节点可能同时扫到同一批候选，
     * 只有 ZREM 返回 1（真的删到了）的实例才处理，避免重复删除。</p>
     */
    public List<String> takeExpiredPending(int limit) {
        try {
            Set<String> candidates = redisTemplate.opsForZSet()
                    .rangeByScore(PENDING_ZSET, 0, System.currentTimeMillis(), 0, limit);
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            List<String> claimed = new ArrayList<>(candidates.size());
            for (String mediaId : candidates) {
                Long removed = redisTemplate.opsForZSet().remove(PENDING_ZSET, mediaId);
                if (removed != null && removed > 0) {
                    claimed.add(mediaId);
                }
            }
            return claimed;
        } catch (Exception e) {
            log.warn("孤儿扫描失败（下次周期重试）: {}", e.getMessage());
            return List.of();
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
