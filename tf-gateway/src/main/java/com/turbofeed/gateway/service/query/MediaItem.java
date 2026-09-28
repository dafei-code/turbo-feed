package com.turbofeed.gateway.service.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.caption.CaptionTagParser;

import java.time.Instant;
import java.util.List;

/**
 * 媒体条目视图（查询侧返回模型，对应"我的上传"列表的一项）。
 *
 * <p><b>为什么单独建模</b>：上传接口只返回 URL 字符串，无法表达"这条内容当前处于
 * 什么审核状态"——而 UGC 场景下前端必须据此过滤展示（仅 APPROVED 可见）。
 * 本模型把"内容标识 + 访问地址 + 审核状态 + 时间 + 描述/标题 + 解析标记"聚合为一项，
 * 供列表接口直接序列化。</p>
 *
 * <p><b>状态来源</b>：{@code status} 不在本模型内持久化，每次查询时由
 * {@link MediaQueryService} 向 {@code MediaReviewService} 实时取——审核状态与内容索引
 * 分属两个职责，避免状态双写不一致（单一事实源）。</p>
 *
 * <p><b>描述/标题（caption）</b>：抖音式文案（@用户、#话题、[image:idx:filename]），
 * 由上传时 {@code CaptionMarkParser} 解析后冗余存 {@code caption_mark}，列表返回
 * 原始 caption + 解析标记，前端直接消费。</p>
 *
 * <p><b>序列化约定（P2）</b>：项目编译未开启 {@code -parameters}，Jackson 在无参数名信息时
 * 无法反序列化 record 的 canonical 构造器。显式 {@link JsonProperty} 绑定 JSON 字段名，
 * 保证 Redis 缓存的 {@code List<MediaItem>} 能稳定往返，且与 IDEA 直接 run 的编译设置无关。</p>
 *
 * <p><b>「一帖多图」（抖音式图文）</b>：{@link #postId} 标识一次上传批次，{@link #images}
 * 是该帖全部图片的 URL（按 {@code seq} 升序），前端据此渲染 9 图轮播。{@link #url} 与
 * {@code images[0]} 等值（首图/封面），{@link #mediaId} 是帖子的<b>代表媒体</b>标识——
 * 二者保留旧字段语义，使旧读取方在灰度期不受影响。</p>
 *
 * <p><b>{@code images} 一定非空</b>：由查询层保证（历史无 post_id 的单图数据回退为
 * {@code [url]} 单元素列表），前端无需判空分支。</p>
 *
 * @param postId      帖子唯一标识（{@code post/{userId}/{uuid}}）；历史遗留单图数据为 {@code null}
 * @param mediaId     帖子的代表媒体标识（首图的 {@code media/{userId}/{uuid}.{ext}}）
 * @param url         首图可访问地址（封面），与 {@code images[0]} 等值
 * @param images      帖内全部图片 URL，按 {@code seq} 升序（前端轮播顺序），保证非空
 * @param seq         代表行在帖内的序号（帖子视图恒为 0；行级视图为真实序号）
 * @param status      当前审核状态（未落库前为进程内态，重启丢失）
 * @param createdAt   内容创建（上传）时间
 * @param caption     描述/标题（原始文本，前端展示）
 * @param captionMark 描述解析后的结构化标记 JSON（@用户 / #话题 / 图片引用）
 * @param tags        caption 中解析出的话题标签（小写、去重、按出现顺序；见
 *                   {@link CaptionTagParser}）。本模型在便捷构造器内<b>直接从 caption 派生</b>，
 *                   因此调用方无需手动传标签；从引擎契约反向映射时（{@link #MediaItem} 规范构造器）
 *                   则透传引擎已物化的 {@code tags}，避免二次解析产生不一致。
 */
public record MediaItem(
        @JsonProperty("postId") String postId,
        @JsonProperty("mediaId") String mediaId,
        @JsonProperty("url") String url,
        @JsonProperty("images") List<String> images,
        @JsonProperty("seq") Integer seq,
        @JsonProperty("status") MediaStatus status,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("caption") String caption,
        @JsonProperty("captionMark") String captionMark,
        @JsonProperty("tags") List<String> tags) {

    /**
     * 单行视图（非帖子聚合）便捷构造：{@code images} 回退为 {@code [url]}、{@code seq} 为 0，
     * {@code tags} 由 caption 直接派生（{@link CaptionTagParser#parse}）。
     *
     * <p>用于「一条 media 记录」的中间态（行级读取、审核链路构造时间线条目前身）。
     * 帖子聚合视图应由仓储层显式传入完整 {@code images}，不要用本构造器。</p>
     */
    public MediaItem(String postId, String mediaId, String url,
                     MediaStatus status, Instant createdAt, String caption, String captionMark) {
        this(postId, mediaId, url,
                url == null ? List.of() : List.of(url),
                0, status, createdAt, caption, captionMark,
                CaptionTagParser.parse(caption));
    }

    /**
     * 时间线的<b>幂等键</b>：优先 {@link #postId}，历史单图数据（无 postId）回退 {@link #mediaId}。
     *
     * <p>与引擎侧 {@code FeedItemView#timelineKey()} 口径完全一致——同一条内容在「网关投递」
     * 与「引擎物化」两侧必须用同一个键，否则下架/标签索引会落在两个不同索引上。</p>
     */
    public String timelineKey() {
        return postId == null || postId.isBlank() ? mediaId : postId;
    }
}
