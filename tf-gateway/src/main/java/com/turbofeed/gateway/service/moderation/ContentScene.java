package com.turbofeed.gateway.service.moderation;

/**
 * 内容检测场景。
 *
 * <p><b>为什么要显式区分场景</b>：同一句话在不同位置的合规尺度并不相同——
 * 昵称是<b>全站公开展示位</b>（且会被大量复制传播），尺度最严、长度最短；
 * 评论是<b>公开对话</b>，允许口语化；私信属<b>点对点</b>，合规要求最低。
 * 若不分场景，只能取最严尺度一刀切，结果是正常评论被大量误杀——这正是
 * 「有词库但不敢用」的常见死因。</p>
 *
 * <p>场景还决定了长度上限与是否允许进入人工复审（见 {@link ContentSecurityProperties}）。</p>
 */
public enum ContentScene {

    /** 用户昵称：公开展示、传播面最广、长度最短。 */
    NICKNAME,

    /** 内容描述 / 标题（caption）：公开展示，长度较长。 */
    CAPTION,

    /** 评论 / 回复：公开对话，允许口语化表达。 */
    COMMENT
}
