package com.turbofeed.feedengine.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Session 级行为序列</b>（抖音式"你刚看过的，马上再多给你"的精排落地处）。
 *
 * <p><b>为什么需要它，长期/短期画像不够吗</b>：0061/0062 的画像是对用户兴趣的<b>聚合估计</b>，
 * 丢掉了"顺序"与"最近性"——它知道你长期喜欢钓鱼、最近在追钓鱼，但不知道你是
 * "<b>刚连着看了 5 条钓鱼</b>"。序列建模要捕捉的正是这层<b>局部、强时效</b>的信号：
 * 一条内容的标签若与你<b>最近互动过</b>的内容高度重合，即使长期画像里它权重不高，
 * 此刻也该排更前（target-attention 的最简形态）。</p>
 *
 * <p><b>存储</b>：每个用户一个 Redis LIST（{@code tf:user:session:{userId}}），最新互动在表头。
 * 每条记录 = {@code epochMillis|eventWeight|tag1,tag2,...}（纯文本，标签已 sanitize，避免 JSON 解析依赖）。
 * 写时 {@code LPUSH + LTRIM(max)} 保窗口、{@code EXPIRE} 兜底；读时 {@code LRANGE} 取最近 N 条，
 * 按时间距算 recency 权重（半衰期可配）。用 LIST 而非 Hash，是为了天然的有序 + 定长裁剪。</p>
 *
 * <p><b>与画像的关系</b>：画像（InterestService）答"你是什么样的人"，本服务答"你此刻在做什么"。
 * 二者在 {@code LinearWeightedRankingModel} 里叠加——画像给稳定底子，序列给跟手尖峰。
 * 关闭（{@code enabled=false}）后退化为"无 session 信号"，精排回到纯画像+统计，语义不破。</p>
 *
 * <p><b>fail-open</b>：Redis 异常只告警不抛，序列缺失最多让"跟手感"变弱，不阻断浏览。</p>
 */
@Service
public class SessionSequenceService {

    private static final Logger log = LoggerFactory.getLogger(SessionSequenceService.class);

    private final StringRedisTemplate redisTemplate;
    private final TagIndexService tagIndexService;

    private static final String SESSION_PREFIX = "tf:user:session:";

    @Value("${turbofeed.feed.session.enabled:true}")
    private boolean enabled = true;

    /** 序列保留的最大互动条数（窗口大小）。 */
    @Value("${turbofeed.feed.session.max:30}")
    private int maxItems = 30;

    /** 序列整体 TTL（分钟）：用户停手这么久，整份序列被清掉。 */
    @Value("${turbofeed.feed.session.ttl-minutes:120}")
    private long ttlMinutes = 120;

    /** 序列内单条互动的 recency 半衰期（分钟）：越久的互动对当前排序影响越小。 */
    @Value("${turbofeed.feed.session.half-life-minutes:30}")
    private double halfLifeMinutes = 30.0;

    public SessionSequenceService(StringRedisTemplate redisTemplate, TagIndexService tagIndexService) {
        this.redisTemplate = redisTemplate;
        this.tagIndexService = tagIndexService;
    }

    /**
     * 记录一次正向外互动到用户的 session 序列。
     *
     * @param userId      行为用户（匿名/null 忽略）
     * @param timelineKey 被互动内容的帖身份（用于反查标签）
     * @param weight      该行为的画像权重（>0 才记；曝光/划走/负反馈不进序列）
     */
    public void record(String userId, String timelineKey, double weight) {
        if (!enabled || userId == null || timelineKey == null || weight <= 0.0) {
            return;
        }
        try {
            List<String> tags = new ArrayList<>(tagIndexService.tagsOf(timelineKey));
            if (tags.isEmpty()) {
                return; // 无标签谈不上序列兴趣
            }
            StringBuilder sb = new StringBuilder();
            sb.append(System.currentTimeMillis()).append('|').append(weight).append('|');
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                // sanitize：避免 '|'（字段分隔）与 ','（标签分隔）破坏解析
                sb.append(tags.get(i).replace('|', '_').replace(',', '_'));
            }
            String key = SESSION_PREFIX + userId;
            redisTemplate.opsForList().leftPush(key, sb.toString());
            redisTemplate.opsForList().trim(key, 0, Math.max(0, maxItems - 1));
            redisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.warn("session 序列记录失败(不影响主流程): userId={}, key={}, {}", userId, timelineKey, e.getMessage());
        }
    }

    /**
     * 取用户最近的 session 序列（表头 = 最新），每条已带 recency 权重。
     *
     * @param userId 用户（null / 关闭 → 空列表）
     * @param n      取最近 n 条（≤0 取全部保留窗口）
     * @return 最近互动序列（空列表 = 冷 session，调用方退化为无此信号）
     */
    public List<SessionItem> recent(String userId, int n) {
        if (!enabled || userId == null) {
            return List.of();
        }
        try {
            int limit = n <= 0 ? maxItems : Math.min(n, maxItems);
            List<String> raw = redisTemplate.opsForList().range(SESSION_PREFIX + userId, 0, limit - 1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            long now = System.currentTimeMillis();
            double hl = halfLifeMinutes <= 0 ? 0.0 : halfLifeMinutes * 60_000.0;
            List<SessionItem> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                SessionItem it = parse(s, now, hl);
                if (it != null) {
                    out.add(it);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 序列单条：被互动内容的标签 + 距现在的 recency 权重（半衰期衰减）。 */
    public record SessionItem(List<String> tags, double recencyWeight) {
    }

    private static SessionItem parse(String entry, long now, double hlMs) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        int p1 = entry.indexOf('|');
        int p2 = entry.indexOf('|', p1 + 1);
        if (p1 < 0 || p2 < 0) {
            return null;
        }
        long ts;
        try {
            ts = Long.parseLong(entry.substring(0, p1));
        } catch (NumberFormatException ignore) {
            return null;
        }
        String tagsStr = entry.substring(p2 + 1);
        if (tagsStr.isEmpty()) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (String t : tagsStr.split(",")) {
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        if (tags.isEmpty()) {
            return null;
        }
        double recency = hlMs <= 0 ? 1.0 : Math.pow(0.5, (double) Math.max(0, now - ts) / hlMs);
        return new SessionItem(tags, recency);
    }
}
