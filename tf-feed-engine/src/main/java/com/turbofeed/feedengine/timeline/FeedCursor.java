package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * feed/recommended 的 keyset 游标（杜绝 OFFSET 深分页）。
 *
 * <p><b>为什么锚定入流时刻而非上传时刻</b>：推荐流时间线 ZSET 的 score 是
 * {@code approvedAt}（过审入流时刻），不是 {@code FeedItemView#createdAt()}（上传时刻，
 * 见 {@link TimelineSupport#recencyOf}）。翻页要"接着上一条继续"，锚点必须是真正的时间序键，
 * 否则会跳过或重复。游标因此编码 {@code (depth, inflowScore, timelineKey)}——
 * {@code depth} 为<b>绝对翻页序号</b>（0 起），随游标续传逐页 +1，供曝光特征（A1 训练样本）
 * 定位"第几页"，避免 keyset 模式下所有续拉页都被记成第 0 页。</p>
 *
 * <p><b>复用评论 {@code CommentService.Cursor} 思路</b>：游标是<b>不透明 base64 token</b>，前端原样回传，
 * 服务端以 score 为<b>排他下界</b>从 ZSET 直接 seek（O(log N)），不再 materialize 被跳过的成员，
 * 也不在漏斗里按 {@code page*limit} 偏移切片。与评论游标的区别：评论锚定 {@code (createdAt, commentId)}
 * 用于 DB 范围查询；本游标锚定入流时刻，并作为<b>多路召回的统一下界</b>（热点/兴趣/向量/关注/冷启动
 * 各通道只取严格早于游标者），保证翻页严格单调前进、跨页不重复。</p>
 */
public record FeedCursor(double score, String timelineKey, int depth) {

    public static FeedCursor of(double score, String timelineKey) {
        return new FeedCursor(score, timelineKey, 0);
    }

    public static FeedCursor of(double score, String timelineKey, int depth) {
        return new FeedCursor(score, timelineKey, depth);
    }

    /** 编码为不透明 token（空串 = 首页）。格式 {@code depth:score:timelineKey}（depth 为绝对翻页序号）。 */
    public String encode() {
        if (timelineKey == null || timelineKey.isBlank()) {
            return "";
        }
        String raw = depth + ":" + (long) score + ":" + timelineKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码 token → 游标；空/非法 token 视为首页（返回 {@code null}）。 */
    public static FeedCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int firstSep = raw.indexOf(':');
            if (firstSep < 0) {
                return null;
            }
            int depth = Integer.parseInt(raw.substring(0, firstSep));
            String rest = raw.substring(firstSep + 1);
            int secondSep = rest.indexOf(':');
            if (secondSep < 0) {
                return null;
            }
            double score = Double.parseDouble(rest.substring(0, secondSep));
            String key = rest.substring(secondSep + 1);
            return new FeedCursor(score, key, depth);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 该候选是否应被本游标跳过（已下发或游标之前的内容）。
     *
     * <p>规则：score 晚于游标（上界已由 ZSET 读取排除，此为双保险）→ 跳过；
     * score 等于游标且身份不早于游标（即游标所指的最后一条及其之后者）→ 跳过；
     * 其余（严格早于游标，或同分但身份更早者）→ 保留。</p>
     *
     * @param itemScore       候选的入流 score（ZSET 成员 score，即 approvedAt epoch 毫秒）
     * @param itemTimelineKey 候选的帖身份（{@link FeedItemView#timelineKey()}）
     */
    public boolean excludes(double itemScore, String itemTimelineKey) {
        if (itemTimelineKey == null) {
            return true;
        }
        if (itemScore > score) {
            return true;
        }
        if (itemScore == score && itemTimelineKey.compareTo(timelineKey) >= 0) {
            return true;
        }
        return false;
    }
}
