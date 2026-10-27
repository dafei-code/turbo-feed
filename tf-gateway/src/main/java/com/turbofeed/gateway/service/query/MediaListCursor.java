package com.turbofeed.gateway.service.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 「我的内容」列表 keyset 游标（P1：media/mine 由 OFFSET 改为 keyset 分页）。
 *
 * <p><b>为什么锚定 (createdAt, postId) 而非页码</b>：列表按 {@code created_at DESC} 排序，
 * 但同一时刻可能有多条（批量上传同秒），仅用 {@code created_at} 无法稳定剔除「已下发项」，
 * 翻页会重复或漏掉。故游标编码<b>最后一帖的 (created_at 毫秒, post_id)</b>，
 * 服务端以 {@code (created_at < ? OR (created_at = ? AND post_id < ?))} 为排他下界续拉，
 * 翻页严格单调、跨页不重复，且 O(索引定位) 不随翻页深度退化（OFFSET 深翻需用游标前的深分页扫描）。</p>
 *
 * <p><b>不透明 token</b>：base64 URL 无 padding 的 {@code epochMillis:postId}。前端原样回传，
 * 服务端解码后只用其两个字段定位下界，不向客户端暴露 SQL 语义。非法/空 token 视为首页。</p>
 *
 * <p><b>与 feed/recommended 游标的区别</b>：推荐游标锚定入流 score（ZSET），本游标锚定
 * DB 行的 (created_at, post_id)（media 表按 user_id 分片、单分片精准命中）。两者都是 keyset，
 * 只是锚点来源不同；视图结构 {@link MediaListPage} 与 {@code FeedRecommendedPage} 对称。</p>
 */
public record MediaListCursor(long createdAtMillis, String postId) {

    public static MediaListCursor of(long createdAtMillis, String postId) {
        return new MediaListCursor(createdAtMillis, postId);
    }

    /** 编码为不透明 token（空串 = 首页）。格式 {@code epochMillis:postId}。 */
    public String encode() {
        if (postId == null || postId.isBlank()) {
            return "";
        }
        String raw = createdAtMillis + ":" + postId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码 token → 游标；空/非法 token 视为首页（返回 {@code null}）。 */
    public static MediaListCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep < 0) {
                return null;
            }
            long t = Long.parseLong(raw.substring(0, sep));
            String pid = raw.substring(sep + 1);
            return new MediaListCursor(t, pid);
        } catch (Exception e) {
            return null;
        }
    }
}
