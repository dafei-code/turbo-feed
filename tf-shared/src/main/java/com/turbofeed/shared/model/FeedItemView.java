package com.turbofeed.shared.model;

import java.time.Instant;
import java.util.List;

/**
 * 公域 Feed 条目的跨服务契约（tf-gateway ⇄ tf-feed-engine）。
 *
 * <p><b>为什么落在 tf-shared</b>：服务拆分后，Feed 时间线读模型（Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}）的读写双方分属两个部署单元——引擎负责物化与读取，
 * 网关负责在审核/下架时投递变更。条目模型必须被双方同时引用，按 service-split.md §2
 * 「契约归 tf-shared」原则放在本模块，而不是各自复制一份。</p>
 *
 * <p><b>字段与既有数据双向兼容</b>：组件名与网关内部视图 {@code MediaItem} 的
 * {@code @JsonProperty} 绑定名<b>逐字相同</b>（postId / mediaId / url / images / status /
 * createdAt / caption / captionMark），Jackson 默认序列化输出亦一致。因此 {@code tf:feed:tl:*}
 * 中<b>已物化的历史成员串无需任何数据迁移</b>即可被本记录反序列化——历史串只有后 6 个字段，
 * 缺失的 {@code postId} / {@code images} 反序列化为 {@code null}，由 {@link #timelineKey()} 与
 * {@link #imageUrls()} 统一回退为「单图帖」语义。</p>
 *
 * <p><b>status 为什么是 String 而不是枚举</b>：审核状态机（{@code MediaStatus}）是网关内部的
 * 业务概念，把它提升到契约层会让引擎被网关的状态机演进绑死。契约只承诺「状态的字符串表示」，
 * 网关侧负责双向映射，引擎侧只需判断是否等于 {@link #STATUS_APPROVED}。</p>
 *
 * <p><b>为什么没有 Jackson 注解（重要）</b>：record 的反序列化走规范构造器，Jackson 需要知道
 * 形参名；而形参名默认被编译成 {@code arg0/arg1}（根 pom 未开启 {@code -parameters}）。
 * 本模块的 {@code pom.xml} 单独开启 {@code maven-compiler-plugin} 的 {@code -parameters}，
 * 形参名随 {@code FeedItemView.class} 一起被下游读取——因此既不需要 {@code @JsonProperty}
 * （会破坏本模块「零第三方依赖」定位），也不需要把编译参数扩散到其他模块。</p>
 *
 * <p><b>「一个成员 = 一个帖子」</b>：B2 之后的公域时间线按<b>帖子</b>物化——同一次上传批次的
 * N 张图共用一条成员串（{@link #images}），而不是一张图一条。因此本记录的两个回退方法
 * （{@link #timelineKey()} / {@link #imageUrls()}）是引擎幂等键与前端轮播的数据基础：
 * 历史单图数据与新的多图帖子<b>走同一段读取代码</b>，无需分支。</p>
 *
 * @param postId      帖子唯一标识（{@code post/{userId}/{uuid}}）；历史数据为 {@code null}，
 *                    此时由 {@link #timelineKey()} 回退用 {@code mediaId} 作为帖子身份
 * @param mediaId     帖子的<b>代表媒体</b>标识——首图的 {@code media/{userId}/{uuid}.{ext}}。
 *                    字段名保持历史语义不变（旧成员串只有它），便于灰度期新旧数据共存
 * @param url         首图可访问地址（列表封面）。与 {@code images[0]} 等值，保留以兼容旧读取方
 * @param images      帖内全部图片的可访问地址，<b>按 {@code seq} 升序</b>（前端轮播顺序）；
 *                    历史数据为 {@code null}，由 {@link #imageUrls()} 回退为单元素列表
 * @param status      审核状态的字符串表示（引擎只认 {@link #STATUS_APPROVED}）
 * @param createdAt   内容创建（上传）时间——仅作展示的发布时间，<b>不参与时间线分桶与排序</b>
 *                    （分桶与 score 取入流时刻，见引擎侧 {@code FeedTimelineStore}）
 * @param caption     描述/标题（原始文本）
 * @param captionMark 描述解析后的结构化标记 JSON（@用户 / #话题 / 图片引用）
 */
public record FeedItemView(
        String postId,
        String mediaId,
        String url,
        List<String> images,
        String status,
        Instant createdAt,
        String caption,
        String captionMark) {

    /** 唯一允许进入公域时间线的状态值（引擎侧写入闸门）。 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 是否可进入公域时间线。 */
    public boolean approved() {
        return STATUS_APPROVED.equals(status);
    }

    /**
     * 时间线的<b>幂等键与反查索引键</b>：优先 {@link #postId}，历史数据（无 postId）回退
     * {@link #mediaId}。
     *
     * <p>引擎侧 {@code FeedTimelineStore} 用它保证「先摘旧位置再写新位置」与下架时的精确
     * {@code ZREM} 对同一帖子生效。调用方<b>必须</b>统一经本方法取键、不要各自拼装，
     * 否则新旧数据会落在两个不同的反查索引上，导致下架失效。</p>
     *
     * <p><b>刻意不用 {@code getTimelineKey()} / {@code isXxx()} 命名</b>：record 上任何
     * {@code get} / {@code is} 前缀的方法都会被 Jackson 当作属性序列化进成员串，凭空污染契约
     * （与 {@code Result<T>.isSuccess()} 同类陷阱）。</p>
     */
    public String timelineKey() {
        return postId == null || postId.isBlank() ? mediaId : postId;
    }

    /**
     * 帖内图片 URL 列表（保证非空且有序）：{@link #images} 缺省时回退为「单图帖」{@code [url]}，
     * 使历史单图数据与新的多图帖子走同一段读取代码。
     */
    public List<String> imageUrls() {
        if (images == null || images.isEmpty()) {
            return url == null ? List.of() : List.of(url);
        }
        return images;
    }
}
