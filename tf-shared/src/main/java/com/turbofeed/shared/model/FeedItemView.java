package com.turbofeed.shared.model;

import java.time.Instant;

/**
 * 公域 Feed 条目的跨服务契约（tf-gateway ⇄ tf-feed-engine）。
 *
 * <p><b>为什么落在 tf-shared</b>：服务拆分后，Feed 时间线读模型（Redis ZSET
 * {@code tf:feed:tl:{pool}:{yyyyMMdd}}）的读写双方分属两个部署单元——引擎负责物化与读取，
 * 网关负责在审核/下架时投递变更。条目模型必须被双方同时引用，按 service-split.md §2
 * 「契约归 tf-shared」原则放在本模块，而不是各自复制一份。</p>
 *
 * <p><b>字段与既有数据完全兼容</b>：组件名与网关内部视图 {@code MediaItem} 的
 * {@code @JsonProperty} 绑定名<b>逐字相同</b>（mediaId / url / status / createdAt /
 * caption / captionMark），Jackson 默认序列化输出亦一致。因此 {@code tf:feed:tl:*} 中
 * <b>已物化的历史成员串无需任何数据迁移</b>即可被本记录反序列化。</p>
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
 * @param mediaId     内容唯一标识（{@code media/{userId}/{uuid}.{ext}}）
 * @param url         可访问地址
 * @param status      审核状态的字符串表示（引擎只认 {@link #STATUS_APPROVED}）
 * @param createdAt   内容创建（上传）时间——仅作展示的发布时间，<b>不参与时间线分桶与排序</b>
 *                    （分桶与 score 取入流时刻，见引擎侧 {@code FeedTimelineStore}）
 * @param caption     描述/标题（原始文本）
 * @param captionMark 描述解析后的结构化标记 JSON（@用户 / #话题 / 图片引用）
 */
public record FeedItemView(
        String mediaId,
        String url,
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
}
