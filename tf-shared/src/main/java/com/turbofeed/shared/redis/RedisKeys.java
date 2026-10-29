package com.turbofeed.shared.redis;

/**
 * 跨服务共享的 Redis key 构造门面（M1 / 3.5）。
 *
 * <p><b>为什么存在</b>：改造前同一个 key 前缀在多个模块里<b>各自硬编码一份</b>——
 * 例如 {@code tf:mod:} 在 tf-gateway（写侧 {@code MediaProperties.Review.Signal#namespace}）
 * 与 tf-feed-engine（读侧 {@code FeedModerationProperties#namespace}）各写一遍，
 * {@code tf:post:stat:}、{@code tf:tag:media:z:}、{@code tf:user:interest:} 同理。
 * 这是本项目已经付过学费的失败模式：Redis 拓扑曾经「两个服务各配一套」，切换集群时只改了网关，
 * 引擎仍连已下线节点 → 时间线读写静默失败 → 推荐流空白。<b>key 前缀的双份定义是同一类定时炸弹</b>：
 * 改一处漏一处不会报错，只表现为「读不到」。</p>
 *
 * <p><b>范围纪律</b>：这里<b>只放跨服务共享</b>的前缀（一侧写、另一侧读，或两侧都写）。
 * 模块私有的 key 留在各服务自己的 {@code RedisKeys} 中，不要为了"统一"而把私有 key 塞进 shared
 * ——那会让契约库随业务膨胀，也模糊了"哪些 key 是跨服务契约"这个更重要的信息。</p>
 *
 * <p><b>零依赖</b>：本类只做字符串拼接，不引入任何第三方库，符合 tf-shared 契约库定位。</p>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ==================== 关注关系（gateway 写 / engine 读）====================

    /** 关注集合：{@code tf:follow:{userId} => ZSET<followeeId, followAtMs>}。 */
    public static final String FOLLOW = "tf:follow:";

    /** 粉丝集合：{@code tf:follower:{userId} => ZSET<followerId, followAtMs>}。 */
    public static final String FOLLOWER = "tf:follower:";

    public static String follow(long userId) {
        return FOLLOW + userId;
    }

    public static String follower(long userId) {
        return FOLLOWER + userId;
    }

    // ==================== 内容统计（engine 写 / gateway 读）====================

    /** 帖子实时统计：{@code tf:post:stat:{postId} => HASH}（完播 / 点赞 / 评论 / 分享）。 */
    public static final String POST_STAT = "tf:post:stat:";

    public static String postStat(String postId) {
        return POST_STAT + postId;
    }

    // ==================== 审核状态（gateway 写，engine 经 tf:mod: 读取信号）====================

    /**
     * 内容审核状态 KV：{@code tf:media:status:{postId}}。
     *
     * <p>由 tf-gateway 写入（审核状态机）；引擎侧走另一条通道
     * {@link #MODERATION_DEFAULT_NAMESPACE} 读外供信号，不直接读本 key。
     * 收敛到这里的原因：本前缀此前在 gateway 内有<b>三份硬编码</b>，
     * 落在同一命名空间下极易单边改动而静默失配。</p>
     */
    public static final String MEDIA_STATUS = "tf:media:status:";

    public static String mediaStatus(String postId) {
        return MEDIA_STATUS + postId;
    }

    // ==================== 标签倒排（engine 写 / gateway 读）====================

    /** 标签 → 内容集合：{@code tf:tag:media:{tag} => SET<timelineKey>}。 */
    public static final String TAG_MEDIA = "tf:tag:media:";

    /** 标签 → 内容有序集合：{@code tf:tag:media:z:{tag} => ZSET<timelineKey, score>}。 */
    public static final String TAG_MEDIA_ORDERED = "tf:tag:media:z:";

    public static String tagMedia(String tag) {
        return TAG_MEDIA + tag;
    }

    public static String tagMediaOrdered(String tag) {
        return TAG_MEDIA_ORDERED + tag;
    }

    // ==================== 兴趣画像（engine 写 / gateway 读）====================

    /** 用户长期兴趣画像：{@code tf:user:interest:{userId} => ZSET<tag, weight>}。 */
    public static final String USER_INTEREST = "tf:user:interest:";

    /** 用户短期（会话级）兴趣画像：{@code tf:user:interest:recent:{userId} => ZSET<tag, weight>}。 */
    public static final String USER_INTEREST_RECENT = "tf:user:interest:recent:";

    public static String userInterest(String userId) {
        return USER_INTEREST + userId;
    }

    public static String userInterestRecent(String userId) {
        return USER_INTEREST_RECENT + userId;
    }

    // ==================== 用户收藏列表（gateway 写 / engine 读）====================

    /**
     * 「我的收藏」列表 ZSET：{@code tf:favorite:user:{userId} => ZSET<postId, score=收藏时刻ms>}。
     *
     * <p>由 tf-gateway 写入（收藏主链路 {@code MediaFavoriteService#addToUserFav}），
     * tf-feed-engine 的 item-CF 召回通道（{@code ItemCfRecallChannel}）作为<b>种子来源</b>只读——
     * 取用户近期收藏的内容身份（timelineKey）去查它们的共现邻居。收敛到这里的原因同
     * {@link #FOLLOW}：此前网关内 {@code MediaFavoriteService#USER_FAV_KEY_PREFIX} 私有硬编码一份，
     * 引擎侧若再写一份会重演「双份定义、单边改动静默失配」的失败模式。</p>
     */
    public static final String USER_FAVORITE = "tf:favorite:user:";

    public static String userFavorite(long userId) {
        return USER_FAVORITE + userId;
    }

    // ==================== item-CF 共现矩阵（gateway 写 / engine 读）====================

    /**
     * 内容-内容共现邻居 ZSET：{@code tf:cf:item:{postId} => ZSET<neighborPostId, coocWeight>}。
     *
     * <p>记忆式 item-CF（抖音式「看了又看 / 收藏又收藏」补充召回通道）的存储核心：
     * tf-gateway 在收藏 / 取消收藏时对称维护（{@code ItemCooccurrenceService#bumpPair} / {@code decayPair}），
     * tf-feed-engine 的 {@code ItemCfRecallChannel} 以用户收藏为种子查邻居并 merge 去重。
     * 单条 ZSET 即「一篇内容最像它的 N 篇」的轻量共现视图，毫秒级读取、零训练延迟。</p>
     */
    public static final String CF_ITEM = "tf:cf:item:";

    public static String cfItem(String postId) {
        return CF_ITEM + postId;
    }

    // ==================== 审核信号外供 KV（gateway 写 / engine 读）====================

    /** 审核信号 KV 默认命名空间：{@code tf:mod:}（命名空间可经配置覆盖）。 */
    public static final String MODERATION_DEFAULT_NAMESPACE = "tf:mod:";

    /**
     * 审核信号 KV：{@code {namespace}{key}}。
     *
     * <p><b>namespace 可配置时仍走这里</b>：写侧（gateway {@code MediaProperties.Review.Signal}）
     * 与读侧（engine {@code FeedModerationProperties}）必须解析到同一命名空间，
     * 因此空值回退口径统一收敛在本方法，避免两侧各写一份默认值。</p>
     */
    public static String moderation(String namespace, String key) {
        String ns = (namespace == null || namespace.isBlank()) ? MODERATION_DEFAULT_NAMESPACE : namespace;
        return ns + key;
    }
}
