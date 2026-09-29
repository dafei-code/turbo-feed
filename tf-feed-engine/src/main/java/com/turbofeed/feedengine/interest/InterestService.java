package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户兴趣画像（Redis Hash {@code tf:user:interest:{userId}}：tag → 累计权重）。
 *
 * <p><b>累积来源</b>：行为埋点（点赞 / 评论 / 分享 / 完播 / 不感兴趣）。权重约定：
 * <ul>
 *   <li>{@code LIKE}=+1.0，{@code COMMENT}/{@code SHARE}=+1.5（强意图）；</li>
 *   <li>{@code WATCH}=<b>按观看进度分段</b>（划走 0 / 看一半 +0.1 / 看完 +0.3，见 {@link #weightOf}）；</li>
 *   <li>{@code PLAY_COMPLETE}=+0.3（旧端二进制完播，等同看完档）；</li>
 *   <li>{@code DISLIKE}=-1.5（负向，降低该标签召回）；</li>
 *   <li>{@code IMPRESSION} 不计权重（曝光不表达偏好，避免刷屏内容吸走画像）。</li>
 * </ul>
 * 权重经 {@link TagIndexService#tagsOf} 把"被互动的内容"映射到其标签——埋点只带 timelineKey，
 * 不带标签，故必须反查标签索引。</p>
 *
 * <p><b>时间衰减（0061）</b>：权重不再是"只增不减的终身累计和"，而是
 * <b>按半衰期指数衰减</b>——距上次更新 {@code halfLifeDays} 天的那部分权重只剩一半。
 * 数学上等价于"每个历史贡献各自按自己的年龄衰减"（每次写入把旧总量乘同一个因子即可，
 * 因为同一半衰期下所有历史贡献经历的衰减量相同）。实现见 {@link #LUA_ACCUMULATE}。</p>
 *
 * <p><b>「不感兴趣」的专属负反馈通道</b>：{@code NOT_INTERESTED} <b>不进权重表</b>，而是把该内容的
 * 标签并入用户级负向标签集 {@code tf:user:dislike-tags:{userId}}（Redis SET），由读路径
 * （{@code FeedTimelineStore#readPage}）对该用户<b>打压同标签内容</b>——这是抖音式负反馈的核心体感：
 * 用户点一次"不感兴趣"，此后<b>所有带该标签的内容</b>（而不是他点过的那一条）在他的流里都会沉底。
 * 与 {@code DISLIKE} 明确分工：后者作用于<b>单条内容</b>（画像负权重 + 流量池全局降级），
 * 前者作用于<b>该用户的相似内容</b>；二者互不干扰，避免把两种语义混成一个信号。</p>
 *
 * <p><b>冷启动</b>：画像为空（新用户 / 未登录）时 {@link #weightedTags} 返回空 Map，
 * 推荐流退化为纯「入流时刻 + 完播率」排序（与改造前一致），绝不因缺画像而报错或空结果。</p>
 *
 * <p><b>为什么必须衰减（对齐抖音）</b>：不衰减的累计和有三个致命后果：
 * <ol>
 *   <li><b>兴趣不可迁移</b>：用户三年前刷过搞笑、现在只看健身，"搞笑"的累计分永远压过"健身"，
 *       画像表达的是"历史总量"而不是"现在的偏好"；</li>
 *   <li><b>悬崖式失效</b>：只靠整份 TTL，29 天和 31 天的差别是 100% → 0%，中间没有任何过渡；</li>
 *   <li><b>画像被噪声固化</b>：早期大量随机行为的累计分会长期锁住召回位。</li>
 * </ol>
 * 抖音的做法是<b>长期兴趣（慢衰减，半衰期以周计）与短期兴趣（快衰减，以小时/天计）分层</b>；
 * 本期先落<b>单画像 + 可配半衰期</b>（把"长期/短期两层"留作下一步），因为它是后续分层的前提：
 * 没有衰减，短期兴趣层也无法从长期画像里分离出来。</p>
 *
 * <p><b>fail-open</b>：Redis 异常只告警不抛，画像缺失最多让个性化失效（回退冷启动），不阻断浏览。</p>
 */
@Service
public class InterestService {

    private final StringRedisTemplate redisTemplate;
    private final TagIndexService tagIndexService;

    private static final String INTEREST_PREFIX = "tf:user:interest:";
    /** 「不感兴趣」行为类型（负反馈专属通道）。 */
    private static final String NEGATIVE_TYPE = "NOT_INTERESTED";
    /**
     * 用户负向标签集 {@code tf:user:dislike-tags:{userId}}（Redis SET）。
     *
     * <p>与画像 Hash 分离的原因：画像用权重表达"多喜欢"，负反馈用<b>集合</b>表达"不要这个标签"，
     * 二者语义与消费方都不同（前者参与加成，后者触发沉底惩罚），混存会让负反馈被相似的兴趣
     * 权重抵消而失效——用户明明点过"不感兴趣"，仍可能因为同标签其它内容的高权重被捞回来。</p>
     */
    private static final String NEG_PREFIX = "tf:user:dislike-tags:";
    private static final Duration TTL = Duration.ofDays(30);

    /**
     * 画像 Hash 里的<b>时间戳哨兵字段</b>：记录最后一次写入的 epoch 毫秒，衰减按它与当前时间的差计算。
     *
     * <p><b>为什么把它塞进同一个 Hash 而不是另开一个 ts key</b>：Redis 集群下 Lua 脚本的多个 KEY
     * 必须落在同一个 slot（否则 {@code CROSSSLOT}），要保证这点就得把 userId 改写成 hash tag
     * （{@code tf:user:interest:{123}}）——那会<b>改变已有键名</b>，让所有已经存在的画像瞬间失联
     * （且现存清理脚本全都指向旧键名）。放在同一个 Hash 里，脚本只碰 <b>一个</b> KEY，
     * 天然同 slot，键名一个字都不用改。</p>
     *
     * <p><b>代价</b>：读路径必须跳过这个哨兵字段——约定以 {@code __} 开头/结尾的字段都不是兴趣标签。</p>
     */
    private static final String TS_FIELD = "__ts__";

    /**
     * 兴趣半衰期（天）：距今 halfLifeDays 天的那部分权重，只剩一半。
     *
     * <p>14 天是"月度级偏好迁移"的量级；调小 → 画像更贴最近行为（更敏感也更抖），
     * 调大 → 更稳定但迁移慢。这是推荐系统里典型的<b>稳定性 vs 时效性</b>旋钮。</p>
     */
    @Value("${turbofeed.feed.interest.half-life-days:14}")
    private double halfLifeDays = 14.0;

    /**
     * 衰减后绝对值低于该值的标签直接移出画像（"遗忘"）。
     *
     * <p><b>为什么需要</b>：指数衰减只会趋近 0 不会到 0，若不清掉，画像里会永久躺着一批
     * {@code 1e-9} 级的长尾标签，占内存且污染 TopN 的比较。
     * 取 0.01 意味着"连一次不完整观看（0.1）的十分之一都不到"，语义上等于已经不在乎了。</p>
     */
    @Value("${turbofeed.feed.interest.min-score:0.01}")
    private double minScore = 0.01;
    // ==================================================================
    // 完播加权的阈值与权重（WATCH / PLAY_COMPLETE）
    //
    // 改造前 WATCH 一律 +0.2，**不看观看时长**：划走 5% 和看完 100% 对画像贡献相同。
    // 后果是画像被"划过"噪声主导——用户手指一滑就给该标签加了分，
    // 而真正看完的强信号被稀释到和划走一样。完播率只在 PostStatService 里算，没喂给画像。
    // ==================================================================
    /** 观看进度低于该比例视为"划走"，**不计入画像**（划走不表达兴趣）。 */
    @Value("${turbofeed.feed.interest.watch-skip-ratio:0.3}")
    private double watchSkipRatio = 0.3;
    /** 看到一半（≥ skip 且 < 完播阈值）的权重：弱信号。 */
    @Value("${turbofeed.feed.interest.watch-partial-weight:0.1}")
    private double watchPartialWeight = 0.1;
    /** 看完（≥ 完播阈值）的权重：与 PostStatService#PLAY_COMPLETE_RATIO 同口径。 */
    @Value("${turbofeed.feed.interest.watch-complete-weight:0.3}")
    private double watchCompleteWeight = 0.3;
    /** 旧端二进制完播事件 {@code PLAY_COMPLETE} 的权重（发即代表看完，等同看完档）。 */
    @Value("${turbofeed.feed.interest.play-complete-weight:0.3}")
    private double playCompleteWeight = 0.3;
    /** 完播判定阈值，与 {@code PostStatService} 保持一致（0.7）。 */
    private static final double COMPLETE_RATIO = 0.7;

    /**
     * 兴趣召回参与轮转的标签数上限（取权重最高的若干个）。
     *
     * <p>刻意小于排序用的 TopN：召回每多一个标签就多一次 {@code SMEMBERS}，
     * 且长尾标签（权重极低）召出来的内容与用户兴趣相关性本就弱，性价比低。</p>
     */
    private static final int RECALL_TAGS = 10;

    /**
     * 行为类型 → 兴趣权重（未知类型按 0 处理，不计画像）。
     *
     * <p><b>刻意不含 {@code WATCH} 与 {@code PLAY_COMPLETE}</b>：这两个是按观看进度
     * 在 {@link #weightOf} 里分段计算的，放这里是<b>不可达的死配置</b>——
     * 留着会让人以为改这张表能调整 WATCH 权重（实际毫无作用）。</p>
     */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "LIKE", 1.0,
            "COMMENT", 1.5,
            "SHARE", 1.5,
            "DISLIKE", -1.5);

    /**
     * 画像写入（原子：先整体衰减 → 再加本次权重 → 更新时间戳与 TTL）。
     *
     * <p><b>为什么必须是 Lua</b>：衰减是"读出全部 → 逐个乘因子 → 写回"的读改写（RMW）。
     * 放在 Java 里做，同一用户的并发行为事件（前端是 2s 批量上报，
     * 一次请求里几十条事件并发处理）会互相覆盖，丢掉一部分权重。
     * Lua 在 Redis 内单线程执行，天然原子。</p>
     *
     * <p><b>为什么写入要负责衰减、而读只计算不回写</b>：读路径做同样的公式（见
     * {@link #decayFactor}）但<b>不写回 Redis</b>。写路径必须衰减是因为要把历史总量"折算"到当前，
     * 否则数值只增不减；读路径不写回则纯粹是成本考虑——写完时间戳已是最新，
     * 读时乘一次因子就能得到当前值，没必要为省这点计算去多一次 Redis 写。</p>
     *
     * <pre>
     * KEYS[1] = tf:user:interest:<userId>
     * ARGV[1] = now(ms)  ARGV[2] = halfLife(ms)  ARGV[3] = minScore
     * ARGV[4] = ttl(s)   ARGV[5] = delta         ARGV[6] = 哨兵字段名
     * ARGV[7..] = 本次要加权的 tag 列表
     * </pre>
     */
    private static final String LUA_ACCUMULATE =
            "local key, tsField = KEYS[1], ARGV[6]\n"
            + "local now = tonumber(ARGV[1])\n"
            + "local halfLife = tonumber(ARGV[2])\n"
            + "local minScore = tonumber(ARGV[3])\n"
            + "local ttl = tonumber(ARGV[4])\n"
            + "local delta = tonumber(ARGV[5])\n"
            + "local raw = redis.call('HGET', key, tsField)\n"
            + "local last = nil\n"
            + "if raw then last = tonumber(raw) end\n"
            + "local factor = 1.0\n"
            + "if last and now > last and halfLife > 0 then\n"
            + "  factor = math.pow(0.5, (now - last) / halfLife)\n"
            + "end\n"
            + "if factor < 1.0 then\n"
            + "  local all = redis.call('HGETALL', key)\n"
            + "  for i = 1, #all, 2 do\n"
            + "    if all[i] ~= tsField then\n"
            + "      local w = tonumber(all[i + 1])\n"
            + "      if w == nil then\n"
            + "        redis.call('HDEL', key, all[i])\n"
            + "      else\n"
            + "        local decayed = w * factor\n"
            + "        if math.abs(decayed) < minScore then\n"
            + "          redis.call('HDEL', key, all[i])\n"
            + "        else\n"
            + "          redis.call('HSET', key, all[i], tostring(decayed))\n"
            + "        end\n"
            + "      end\n"
            + "    end\n"
            + "  end\n"
            + "end\n"
            + "for i = 7, #ARGV do\n"
            + "  redis.call('HINCRBYFLOAT', key, ARGV[i], delta)\n"
            + "end\n"
            + "redis.call('HSET', key, tsField, tostring(now))\n"
            + "if ttl > 0 then redis.call('EXPIRE', key, ttl) end\n"
            + "return redis.call('HLEN', key)\n";

    /** 预编译的脚本对象（避免每次请求重新解析 Lua 源码）。 */
    private final RedisScript<Long> accumulateScript = RedisScript.of(LUA_ACCUMULATE, Long.class);

    /**
     * 时间衰减因子：距上次更新 {@code elapsedMs} 毫秒后，历史权重应乘的系数。
     *
     * <p>⚠️ <b>与 Lua 里的实现是镜像关系</b>（{@code 0.5^(elapsed/halfLife)}），改一处必须改另一处。
     * 之所以不复用：Lua 跑在 Redis 里没法调 Java，而为了让"读"也反映当前时间，
     * 读路径必须能在不落盘的前提下算一次。</p>
     */
    static double decayFactor(long elapsedMs, double halfLifeMs) {
        if (halfLifeMs <= 0 || elapsedMs <= 0) {
            return 1.0;
        }
        return Math.pow(0.5, (double) elapsedMs / halfLifeMs);
    }

    /** 半衰期换算成毫秒（顺带做非法值兜底：配置填 0 或负数等于关闭衰减）。 */
    private double halfLifeMs() {
        return halfLifeDays <= 0 ? 0.0 : halfLifeDays * 86_400_000.0;
    }

    public InterestService(StringRedisTemplate redisTemplate, TagIndexService tagIndexService) {
        this.redisTemplate = redisTemplate;
        this.tagIndexService = tagIndexService;
    }

    /**
     * 处理一条行为事件：解析其标签并累加到对应用户画像（fail-open）。
     *
     * <p>匿名埋点（{@code userId == null}，理论不存在，因为上报接口要求登录）直接忽略；
     * 该内容无标签（{@code tagsOf} 为空）也忽略——没有标签就谈不上兴趣。</p>
     *
     * <p>{@code NOT_INTERESTED} 走 {@link #recordNegativeTags} 专属通道，不落到权重表里。</p>
     */
    public void accumulateFromEvent(FeedBehaviorEvent event) {
        if (event == null || event.userId() == null || event.timelineKey() == null) {
            return;
        }
        String type = event.type() == null ? "" : event.type().toUpperCase();
        if (NEGATIVE_TYPE.equals(type)) {
            // 抖音式负反馈：不是"给画像减分"，而是"以后别再给我推这类内容"
            recordNegativeTags(event.userId(), event.timelineKey());
            return;
        }
        double weight = weightOf(type, event);
        if (weight == 0.0) {
            return; // 曝光、划走等不计画像
        }
        List<String> tags = new ArrayList<>(tagIndexService.tagsOf(event.timelineKey()));
        if (tags.isEmpty()) {
            return;
        }
        try {
            String key = INTEREST_PREFIX + event.userId();
            List<String> argv = new ArrayList<>(6 + tags.size());
            argv.add(String.valueOf(System.currentTimeMillis()));
            argv.add(String.valueOf(halfLifeMs()));
            argv.add(String.valueOf(minScore));
            argv.add(String.valueOf(TTL.getSeconds()));
            argv.add(String.valueOf(weight));
            argv.add(TS_FIELD);
            argv.addAll(tags);
            redisTemplate.execute(accumulateScript, List.of(key), argv.toArray(new Object[0]));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InterestService.class)
                    .warn("兴趣画像累积失败（不影响主流程）: userId={}, type={}, {}",
                            event.userId(), event.type(), e.getMessage());
        }
    }

    /**
     * 行为 → 画像权重。{@code WATCH} / {@code PLAY_COMPLETE} 走完播分段，其余查表。
     *
     * <p><b>为什么要分段</b>：观看进度是<b>连续信号</b>，压成固定权重等于丢掉信息。
     * "划走"其实是最常见的负向信号（用户用脚投票），改造前它和"看完"一样给 +0.2，
     * 画像因此被大量划走噪声主导。</p>
     *
     * <p><b>{@code PLAY_COMPLETE} 为什么要单独处理</b>：旧端只发这个二进制事件
     * （看完即发），它此前<b>不在权重表里</b> → 老端的完播行为完全不进画像。
     * 语义上它等同于"看完"，故取看完档权重。</p>
     */
    private double weightOf(String type, FeedBehaviorEvent event) {
        if ("WATCH".equals(type)) {
            return watchWeight(event);
        }
        if ("PLAY_COMPLETE".equals(type)) {
            return playCompleteWeight;
        }
        return WEIGHTS.getOrDefault(type, 0.0);
    }

    /**
     * 按观看进度折算权重：划走不计 / 看一半弱信号 / 看完强信号。
     *
     * <p><b>缺时长字段时按"看一半"处理</b>（弱信号）：宁可保守也不把未知当"看完"，
     * 否则一次字段缺失就把噪声当成强兴趣灌进画像。</p>
     */
    private double watchWeight(FeedBehaviorEvent event) {
        Integer wd = event.watchDuration();
        Integer md = event.mediaDuration();
        if (wd == null || md == null || md <= 0) {
            return watchPartialWeight;
        }
        double ratio = (double) wd / md;
        if (ratio < watchSkipRatio) {
            return 0.0;                 // 划走：不表达兴趣，一分不加
        }
        return ratio >= COMPLETE_RATIO ? watchCompleteWeight : watchPartialWeight;
    }

    /**
     * 记录「不感兴趣」负反馈：把目标内容的<b>全部标签</b>并入该用户的负向标签集。
     *
     * <p><b>为什么并入"全部标签"而不是只记被点的那条内容</b>：负反馈的语义是
     * "这类内容我不想看"，记标签才能泛化到其它同标签内容；只记 timelineKey 则只能过滤单条，
     * 用户会在同一类内容上反复点"不感兴趣"，体感极差（这正是抖音把它做成标签级 suppression 的原因）。</p>
     *
     * <p>幂等（SET 天然去重），且受同一份 {@link #TTL} 约束——长期不活跃的负反馈会自然过期，
     * 避免"三年前点过一次不感兴趣"永久影响推荐。</p>
     *
     * <p>fail-open：写入失败只告警，最多让本次负反馈不生效，不影响浏览。</p>
     */
    public void recordNegativeTags(String userId, String timelineKey) {
        Set<String> tags = tagIndexService.tagsOf(timelineKey);
        if (tags == null || tags.isEmpty()) {
            // 无标签内容：没有可泛化的维度，本次负反馈无从表达，静默忽略
            return;
        }
        try {
            String key = NEG_PREFIX + userId;
            redisTemplate.opsForSet().add(key, tags.toArray(new String[0]));
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InterestService.class)
                    .warn("负反馈标签写入失败（不影响主流程）: userId={}, timelineKey={}, {}",
                            userId, timelineKey, e.getMessage());
        }
    }

    /**
     * 取用户的负向标签集（读路径据此对相似内容应用沉底惩罚）。
     *
     * @param userId 用户（{@code null} → 空集，匿名/冷启动不做任何打压）
     * @return 负向标签集合；无/异常返回空集（fail-open，绝不影响可见性）
     */
    public Set<String> negativeTags(String userId) {
        if (userId == null) {
            return Set.of();
        }
        try {
            Set<String> tags = redisTemplate.opsForSet().members(NEG_PREFIX + userId);
            return tags == null ? Set.of() : tags;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * 取用户兴趣 TopN 标签及其权重（仅正向权重用于召回/加权；负向不进召回）。
     *
     * <p><b>会做时间衰减</b>：按 {@link #TS_FIELD} 距现在的时长乘衰减因子后返回，
     * 保证读到的永远是"当前还剩下多少兴趣"，而不是历史累计和。</p>
     *
     * @param userId 用户（{@code null} → 空 Map，冷启动）
     * @param n      返回条数上限（≤0 视为不限制）
     * @return tag → 当前权重（按权重降序）；无画像/异常返回空 Map
     */
    public Map<String, Double> weightedTags(String userId, int n) {
        if (userId == null) {
            return Map.of();
        }
        try {
            String key = INTEREST_PREFIX + userId;
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            long lastTs = 0L;
            Object tsRaw = raw.get(TS_FIELD);
            if (tsRaw != null) {
                try {
                    lastTs = Long.parseLong(String.valueOf(tsRaw));
                } catch (NumberFormatException ignore) {
                    // 脏哨兵值：按"时间未知"处理，不衰减（也好过整份画像失效）
                    lastTs = 0L;
                }
            }
            double factor = lastTs > 0 ? decayFactor(System.currentTimeMillis() - lastTs, halfLifeMs()) : 1.0;
            List<Map.Entry<String, Double>> entries = new ArrayList<>();
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                String field = String.valueOf(e.getKey());
                if (TS_FIELD.equals(field) || (field.startsWith("__") && field.endsWith("__"))) {
                    continue; // 哨兵字段不是兴趣标签
                }
                double v;
                try {
                    // StringRedisTemplate 的 Hash 值是 String（如 "2.7"），不能强转 Number（CCE→fail-open 吞掉→画像永远为空）
                    v = Double.parseDouble(String.valueOf(e.getValue())) * factor;
                } catch (NumberFormatException nfe) {
                    continue; // 脏数据：跳过该标签，不能让一条坏数据毁掉整份画像
                }
                if (v > 0) {
                    entries.add(Map.entry(field, v));
                }
            }
            entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<String, Double> result = new LinkedHashMap<>();
            int limit = n <= 0 ? entries.size() : Math.min(n, entries.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Double> en = entries.get(i);
                result.put(en.getKey(), en.getValue());
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * <b>兴趣召回</b>：按用户 TopN 正向标签，经「标签 → 内容」索引取候选
     * （抖音式多路召回里的"兴趣召回"这一路）。
     *
     * <p><b>为什么必须有这一路</b>：画像此前<b>只用于给已召回的内容加权</b>——
     * 候选来自「流量池 + 入流时刻」，画像只能在"已经捞上来的东西"里调顺序。
     * 真正的个性化是"因为你有这个兴趣，所以<b>专门去找</b>这类内容"，
     * 否则用户永远看不到池外/深页的同类好内容。索引（{@link TagIndexService#mediaWithTag}）
     * 早已建好、此前零调用，本次是把这条通路接上。</p>
     *
     * <p><b>为什么用轮转（round-robin）而不是按标签权重填满</b>：
     * 若按权重从高到低依次取满，权重最高的一个标签会吃光所有槽位，
     * 结果就是"你点过一次美食，整页全是美食"——这正是信息茧房的成因。
     * 轮转保证多个兴趣标签各有代表，与后续"同类不连刷"打散层形成互补
     * （召回层管**覆盖**，重排层管**相邻不重复**）。</p>
     *
     * <p>fail-open：任一环节异常返回已收集到的部分（或空列表），
     * 召回缺失只让个性化变弱，绝不影响可见性。</p>
     *
     * @param userId 用户（{@code null} / 无画像 → 空列表，冷启动不做召回）
     * @param limit  期望条数上限（≤0 返回空）
     * @return 候选内容的 timelineKey 列表（去重，多标签轮转顺序）
     */
    public List<String> recallTimelineKeys(String userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        Map<String, Double> tags = weightedTags(userId, RECALL_TAGS);
        if (tags.isEmpty()) {
            return List.of();
        }
        // 负反馈优先于兴趣：用户点过"不感兴趣"的标签，召回阶段就不再捞——
        // 只在排序阶段扣分是不够的：只要它仍在候选里，就总有机会被排上来，
        // 而"别再给我推这类"的语义是**根本不要进候选**。
        Set<String> negative = negativeTags(userId);
        // 每个标签各自的候选（保持画像权重降序，轮转时按此顺序取）
        Map<String, List<String>> perTag = new LinkedHashMap<>();
        for (String tag : tags.keySet()) {
            if (negative.contains(tag)) {
                continue;
            }
            Set<String> keys = tagIndexService.mediaWithTag(tag);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            perTag.put(tag, new ArrayList<>(keys));
        }
        if (perTag.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(limit);
        Set<String> seen = new LinkedHashSet<>();
        // 轮转：第 i 轮从每个标签各取第 i 个（同一内容命中多标签时只算一次）
        int maxLen = 0;
        for (List<String> l : perTag.values()) {
            maxLen = Math.max(maxLen, l.size());
        }
        for (int i = 0; i < maxLen && out.size() < limit; i++) {
            for (List<String> keys : perTag.values()) {
                if (i >= keys.size()) {
                    continue;
                }
                String k = keys.get(i);
                if (k != null && seen.add(k)) {
                    out.add(k);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 用户是否有兴趣画像（冷启动判定）。
     *
     * <p><b>刻意用 O(1) 的 {@code HLEN} 而不是复用 {@link #weightedTags}</b>：本方法是
     * {@code readPage} 每次都走的<b>前置闸门</b>，若它内部再调一次 weightedTags，
     * 一次读请求就会把画像 Hash 扫两遍。</p>
     *
     * <p><b>为什么判据是 {@code size > 1}</b>：加了时间衰减后，画像里除标签还会固定存在哨兵
     * 时间戳字段 {@link #TS_FIELD}，只剩它一个 = 所有标签都已被遗忘，等同于没有画像。
     * 这里只判"有没有标签"，不判"权重是否为正"——后者由 {@link #weightedTags}
     * 过滤（只有正向权重才进召回），两处职责分开。</p>
     */
    public boolean hasInterest(String userId) {
        if (userId == null) {
            return false;
        }
        try {
            Long size = redisTemplate.opsForHash().size(INTEREST_PREFIX + userId);
            return size != null && size > 1;
        } catch (Exception e) {
            return false;
        }
    }
}
