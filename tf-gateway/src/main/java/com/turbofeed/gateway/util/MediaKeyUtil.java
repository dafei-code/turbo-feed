package com.turbofeed.gateway.util;

/**
 * 媒体 / 帖子键解析（抖音式：作者内嵌在键路径里）。
 *
 * <p>上传时生成的 {@code postId} 形如 {@code post/{userId}/{uuid}}，历史单图帖回退用
 * {@code mediaId} 形如 {@code media/{userId}/{uuid}}（见 {@code MediaUploadedEvent} 与
 * {@code ReviewTimelineDelivery#parseUserId}）。两种格式的第 2 段都是归属作者 userId。</p>
 *
 * <p><b>用途</b>：通知生产方据此零 DB 查询解析「内容作者」，避免为每条点赞 / 收藏 / 评论
 * 触发一次跨分片广播查询（{@code post_id} 不是 media 表分片键）。解析失败返回
 * {@code -1}，调用方据此跳过通知（fail-open）。</p>
 */
public final class MediaKeyUtil {

    private MediaKeyUtil() {
    }

    /**
     * 从 postId / mediaId 解析归属 userId。
     *
     * @return 解析成功返回 userId（{@code >0}）；键为空 / 格式非法 / 段非数字时返回 {@code -1}
     */
    public static long parseUserId(String key) {
        if (key == null || key.isBlank()) {
            return -1L;
        }
        String[] parts = key.split("/");
        if (parts.length < 2) {
            return -1L;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
