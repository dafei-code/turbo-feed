package com.turbofeed.feedengine.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 关注关系读取（抖音式「关注流 / 社交分发」G6，推荐消费侧）。
 *
 * <p><b>读模型</b>：关注关系由网关写入（同 Redis keyspace），约定
 * {@code tf:follow:{userId}} = ZSET(followedAuthorId → 关注时刻毫秒)。本服务只负责在推荐读路径
 * 读取该社交图，把「关注作者」的近期已审内容混入发现流——<b>不做任何写入</b>，写入留网关。</p>
 *
 * <p><b>fail-open</b>：关系缺失 / Redis 不可达 / 解析异常 → 返回空集，发现流退化为纯公域
 * （热点 + 兴趣 + 向量 + 流量池），绝不影响浏览主流程。</p>
 */
@Component
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);
    /** 关注关系键前缀（网关写入，本服务只读）：tf:follow:{userId} = ZSET(authorId → 关注时刻)。 */
    private static final String FOLLOW_PREFIX = "tf:follow:";

    private final StringRedisTemplate redisTemplate;

    public FollowService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 该用户是否关注了任何人（快速判空，避免无关注时走召回）。fail-open：异常→false。 */
    public boolean hasFollows(String userId) {
        if (userId == null || userId.isEmpty()) {
            return false;
        }
        try {
            Long c = redisTemplate.opsForZSet().zCard(FOLLOW_PREFIX + userId);
            return c != null && c > 0;
        } catch (Exception e) {
            log.warn("关注关系读取失败（fail-open 视为无关注）: userId={}, {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 取该用户关注的用户（按关注时刻倒序，最新关注的在前；限制扫描上限，避免大 V 关注数爆炸）。
     *
     * @param userId 用户
     * @param limit  最多返回的关注数（≤0 → 空集）
     * @return 关注作者 id 列表（顺序 = 关注时刻倒序）；缺失 / 异常 → 空集
     */
    public List<String> followedAuthors(String userId, int limit) {
        if (userId == null || userId.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            Set<String> s = redisTemplate.opsForZSet().reverseRange(FOLLOW_PREFIX + userId, 0, limit - 1);
            return s == null ? List.of() : new ArrayList<>(s);
        } catch (Exception e) {
            log.warn("关注列表读取失败（fail-open 退化为纯公域）: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }
}
