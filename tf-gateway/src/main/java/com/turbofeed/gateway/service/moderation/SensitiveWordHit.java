package com.turbofeed.gateway.service.moderation;

/**
 * 一次敏感词命中的结果（③匹配层的输出契约）。
 *
 * <p><b>为什么不是只返回命中的词串</b>：只给词串，调用方拿不到「在哪个位置命中」。
 * 位置信息有三个必须的用途——前端高亮命中片段、运营核对「是不是误杀」、
 * 按位置切片做人工复核。位置在匹配时是<b>顺手可得</b>的（AC 扫到终结节点时
 * 当前下标减去词长即是起点），事后用 {@code indexOf} 反查则可能拿到另一个位置
 * （同词多次出现时）。</p>
 *
 * <p>位置是<b>归一化文本</b>的下标，不是原始文本的下标。归一化会改变长度
 * （剥离分隔符、NFKC 展开、繁简折叠），所以调用方必须用
 * {@link TextNormalizer.Normalized#toOriginalIndex(int)} 回溯——
 * {@link ContentSecurityService} 已代为完成，对外返回的
 * {@link ContentVerdict#hitStart()} 是<b>原始文本</b>下标。</p>
 *
 * <p>字段命名不带 {@code get} / {@code is} 前缀（Jackson 会把它们当属性序列化），
 * 用 record 的规范访问器即可。</p>
 *
 * @param word     命中的词条（与词库中的存储形态一致）
 * @param category 该词条所属分类（决定处置动作）；词条无分类时为 {@code null}
 * @param start    命中在归一化文本中的起始下标（含）
 * @param end      命中在归一化文本中的结束下标（不含）
 */
public record SensitiveWordHit(String word, String category, int start, int end) {

    /** 命中长度（字符数）。 */
    public int length() {
        return end - start;
    }
}
