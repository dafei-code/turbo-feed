package com.turbofeed.shared.model;

/**
 * 行为埋点事件（网关 → 引擎；经 RocketMQ 或同步 HTTP 承载）。
 *
 * <p><b>零 Jackson 注解</b>：依赖 tf-shared 模块单独开启的 {@code -parameters} 编译参数做反序列化
 * （与 {@link FeedItemView} / {@link FeedTimelineEvent} 同机制），保持本模块零三方依赖定位。</p>
 *
 * <p><b>刻意不用 is/get 前缀方法</b>：record 上任何 is/get 前缀方法会被 Jackson 当属性序列化，
 * 污染消息体（与 {@code FeedTimelineEvent} 同款陷阱）。</p>
 *
 * @param timelineKey  帖身份（{@code postId}；与 {@link FeedItemView#timelineKey()} 口径一致）
 * @param type         行为类型：{@code IMPRESSION}（曝光）/ {@code WATCH}（完播上报，携带观看时长）/
 *                     {@code PLAY_COMPLETE}（完播，二进制，兼容旧端）/ {@code LIKE}（点赞）/
 *                     {@code COMMENT}（评论）/ {@code SHARE}（分享）/ {@code DISLIKE}（不感兴趣/负向）
 * @param watchDuration  观看时长（秒）；仅 {@code WATCH} 使用，由客户端上报
 * @param mediaDuration  内容时长（秒）；仅 {@code WATCH} 使用，视频为真实时长、图文为期望驻留时长。
 *                     服务端据 {@code watchDuration / mediaDuration} 算完播率，无上报时为 {@code null}
 * @param userId        行为发起者；用于累积兴趣画像与个性化召回（见引擎 {@code InterestService}）。
 *                     由网关登录态注入（行为上报接口要求 {@code FEED_INTERACT} 权限，必为已登录用户）。
 *                     引擎侧消费时按"非空才累积"处理，匿名埋点（理论不存在）不污染画像。
 * @param requestId    <b>本次推荐请求 ID</b>（客户端拉取列表时生成，同一次刷新的所有条目共用）。
 *                     作用：把"当次展示了哪些内容"归组——没有它就无法把「曝光了但没互动」正确判定为负样本，
 *                     也无法还原一次请求内的候选集合（训练样本的必要条件）。可为 {@code null}（旧端兼容）。
 * @param position     该条目在<b>当次请求</b>结果里的位次（0 起）。用于 <b>position bias 校正</b>：
 *                     越靠前越容易被看到/点击，若不校正，模型会把"位置靠前"误学成"内容更好"。
 *                     可为 {@code null}（旧端兼容）。
 *
 * <p><b>为什么不落"服务端排序分 score"</b>：分数随模型/权重版本变化，历史分数与新分数不可比，
 * 落盘反而污染训练集。离线训练时应由<b>特征重算</b>（同一份特征代码在样本时间点重放）得到，
 * 这也是特征存储要解决的核心问题（训练- serving 一致性）。</p>
 */
public record FeedBehaviorEvent(String timelineKey, String type, Integer watchDuration, Integer mediaDuration,
                                String userId, String requestId, Integer position) {
}
