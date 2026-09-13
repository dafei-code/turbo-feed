package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 公域推荐流读取服务：时间线读取 + 短 TTL 旁路缓存。
 *
 * <p><b>为什么缓存放在引擎而不是网关</b>：缓存是"离数据最近"才有效的组件。时间线 ZSET 在引擎
 * 进程的 Redis 里读写，若网关再压一层缓存，就会出现"两层缓存各自过期、失效路径要跨进程通知"
 * 的复杂度；拆分的意义之一就是让读模型与其缓存同域。网关因此退化为纯转发 + 降级决策。</p>
 *
 * <p><b>TTL 取舍</b>：推荐流缓存 TTL 取 15s（{@link #REC_CACHE_TTL}），以最终一致换简单与无阻塞
 * ——发布/下架<b>不</b>主动批量清推荐流缓存（按 key 通配清除需要 SCAN，在热路径上是禁忌），
 * 代价是内容变更后最长 15s 才在发现流体现。这与原有的"下架立即停推"并不冲突：下架同时会
 * 精确 {@code ZREM} 时间线，缓存过期即消失；真正的合规兜底依内容安全流程，而非缓存时延。</p>
 *
 * <p><b>fail-open</b>：缓存读写任一环节异常都只告警，直接回落到时间线实时读取；时间线本身
 * 也 fail-open（见 {@link FeedTimelineStore}），因此本服务永不因 Redis 异常向调用方抛错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendedFeedService {

    private final FeedTimelineStore feedTimelineStore;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 推荐流缓存 TTL：15s（短 TTL 换最终一致，避免批量失效 Redis 阻塞）。 */
    private static final Duration REC_CACHE_TTL = Duration.ofSeconds(15);
    private static final String REC_KEY_PREFIX = "tf:feed:rec:";

    /**
     * 读取公域推荐流（入流时间倒序，分页）。
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数（≤0 兜底 20）
     * @return 当前页内容；空表示无更多内容或 Redis 不可用（调用方据此决定降级口径）
     */
    public List<FeedItemView> recommended(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        String key = REC_KEY_PREFIX + page + ":" + limit;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FeedItemView.class));
            }
        } catch (Exception e) {
            log.warn("推荐流缓存读取失败，回落时间线实时读取: key={}, {}", key, e.getMessage());
        }

        List<FeedItemView> fresh = feedTimelineStore.readPage(page, limit);

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fresh), REC_CACHE_TTL);
        } catch (Exception e) {
            log.warn("推荐流缓存写入失败（不影响本次读取）: key={}, {}", key, e.getMessage());
        }
        return fresh;
    }
}
