package com.turbofeed.gateway.service.moderation;

/**
 * 一次内容检测的裁定结果（①契约层）。
 *
 * <p>把「处置动作 + 命中详情 + 是否被白名单豁免」一次性返回，而不是只返回一个布尔值——
 * 调用方（写路径）需要知道是否拒绝；运营后台需要知道命中哪个词、什么分类；
 * 排障需要知道「为什么这次没拦」。这些信息如果不在返回值里，就只能靠日志拼，无法统计与回放。</p>
 *
 * <p><b>字段命名刻意不带 {@code get} / {@code is} 前缀</b>：本 record 会被 admin 接口直接序列化，
 * 而带前缀的方法会被 Jackson 当成属性一起写进 JSON（本项目在 {@code FeedTimelineEvent} 与
 * {@code Result#isSuccess} 上踩过两次）。用 record 的规范访问器即可，无需注解。</p>
 *
 * @param action      处置动作（唯一具有强制语义的字段）
 * @param scene       检测场景
 * @param matchedWord 命中的敏感词；未命中为 {@code null}
 * @param category    命中词所属分类（决定动作的依据）；未命中为 {@code null}
 * @param whitelisted 是否因白名单豁免——为真时 {@code action} 必为 {@link ModerationAction#PASS}，
 *                    但仍保留 {@code matchedWord} 供统计「哪些词最容易误杀」
 * @param hitStart    命中在<b>原始文本</b>中的起始下标（含）；未命中或归一化后长度变化无法回溯时为 -1
 * @param hitEnd      命中在原始文本中的结束下标（不含）；同上
 */
public record ContentVerdict(
        ModerationAction action,
        ContentScene scene,
        String matchedWord,
        String category,
        boolean whitelisted,
        int hitStart,
        int hitEnd) {

    /** 本次检测是否应阻断发布（BLOCK / REVIEW）。 */
    public boolean denies() {
        return action.denies();
    }

    /** 未命中的放行结果。 */
    public static ContentVerdict pass(ContentScene scene) {
        return new ContentVerdict(ModerationAction.PASS, scene, null, null, false, -1, -1);
    }

    /** 命中但被白名单豁免。 */
    public static ContentVerdict exempt(ContentScene scene, String word, String category) {
        return new ContentVerdict(ModerationAction.PASS, scene, word, category, true, -1, -1);
    }
}
