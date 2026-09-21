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
 * <p><b>TTL + 主动失效（两段式）</b>：TTL 取 15s（{@link #REC_CACHE_TTL}）只是<b>兜底</b>；
 * 时间线发生 append / remove 时由 {@link #invalidate()} 主动精确 {@code DEL} 缓存 key，
 * 让新内容<b>立刻</b>可见。只依赖 TTL 的代价实测很明显：补投一条内容后必须等满 15s 才能在
 * 发现流出现，对用户就是"我发了却看不到"。
 * 失效范围刻意<b>有界</b>——只删「前 {@link #INVALIDATE_PAGES} 页 × 配置页大小」的确定 key，
 * <b>绝不用 SCAN 通配清除</b>（单线程 Redis 上 SCAN 大 keyspace 会阻塞其它命令）。
 * TTL 仍保留，用于兜住配置外的页大小/页码组合。</p>
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

    /** 推荐流缓存 TTL：15s（兜底；正常路径由 {@link #invalidate()} 主动失效）。 */
    private static final Duration REC_CACHE_TTL = Duration.ofSeconds(15);
    private static final String REC_KEY_PREFIX = "tf:feed:rec:";
    /**
     * 主动失效覆盖的页码范围 {@code [0, INVALIDATE_PAGES)}——发现流的绝大多数请求落在前几页，
     * 更深页由 15s TTL 兜底。有界删除是刻意的：不为了"清干净"去 SCAN 整个 keyspace。
     */
    private static final int INVALIDATE_PAGES = 5;
    /** 主动失效覆盖的页大小集合（前端约定的固定值；其余取值由 TTL 兜底）。 */
    private static final int[] INVALIDATE_SIZES = {20, 50};

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

    /**
     * 时间线变更后主动失效推荐流缓存（发布 / 下架 / 补投后调用）。
     *
     * <p><b>为什么不用 SCAN</b>：按 {@code tf:feed:rec:*} 通配扫描再删，在 keyspace 变大后
     * 会长时间占用 Redis 单线程，把一次"内容发布"放大成全局抖动。这里只对
     * 「前 {@link #INVALIDATE_PAGES} 页 × {@link #INVALIDATE_SIZES}」这 10 个确定 key 发
     * {@code DEL}，成本恒定且与数据规模无关。配置外的页大小/页码由 15s TTL 兜底，
     * 不会读到永久脏数据。</p>
     *
     * <p><b>⚠️ 为什么逐个 {@code DEL} 而不是批量 {@code DEL k1 k2 ...}</b>：
     * Redis Cluster 下多 key 命令要求所有 key 落在<b>同一个槽</b>，否则返回
     * {@code CROSSSLOT Keys in request don't hash to the same slot}。这些缓存 key 没有
     * hashtag、天然散落在不同槽，批量删必然报错。逐个删是集群下的正确写法
     * （代价是 N 次 RTT；本方法只在发布/下架/补投时触发，不在读热路径上）。</p>
     *
     * <p><b>fail-open</b>：删除失败只告警——最坏情况退化为"等 TTL 过期"，与改造前一致，
     * 不会让时间线写入（主流程）失败。</p>
     */
    public void invalidate() {
        int removed = 0;
        for (int size : INVALIDATE_SIZES) {
            for (int page = 0; page < INVALIDATE_PAGES; page++) {
                String key = REC_KEY_PREFIX + page + ":" + size;
                try {
                    Boolean ok = redisTemplate.delete(key);
                    if (Boolean.TRUE.equals(ok)) {
                        removed++;
                    }
                } catch (Exception e) {
                    // 单个 key 失败不影响其余；整体退化为 TTL 兜底
                    log.warn("推荐流缓存失效失败（退化为 TTL 到期）: key={}, {}", key, e.getMessage());
                }
            }
        }
        if (removed > 0) {
            log.debug("推荐流缓存已失效: 删除 {} 个 key", removed);
        }
    }
}
