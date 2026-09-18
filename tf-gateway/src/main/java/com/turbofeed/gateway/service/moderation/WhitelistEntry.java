package com.turbofeed.gateway.service.moderation;

/**
 * 白名单条目（误杀治理的出口）。
 *
 * <h3>为什么必须有这一层</h3>
 * 任何词库都必然存在误杀——中文里「黄色」「水乳交融」「打飞机（游戏术语）」这类词，
 * 在某个语境下是完全正常的表达。没有豁免出口时，运营只能做二选一：
 * <b>要么把词删掉（漏放），要么留着天天被投诉（误杀）</b>，两头都错。
 * 白名单把「该词是否敏感」从<b>词本身的属性</b>改成<b>词 × 场景 × 主体</b>的属性，
 * 于是误杀和漏放可以分别调。</p>
 *
 * <h3>两个正交维度</h3>
 * <ul>
 *   <li><b>场景（{@link ContentScene}）</b>：昵称是全站公开展示位，尺度最严；
 *       评论允许口语化。同一个词（如「吐槽」类口语词）在评论里放行、在昵称里仍拦，
 *       这是「取最严尺度一刀切」做不到的。{@value #SCENE_ANY} 表示所有场景。</li>
 *   <li><b>主体（{@code scope}）</b>：{@value #SCOPE_GLOBAL} 是全局豁免（对所有人生效）；
 *       {@value #SCOPE_USER} 是<b>单用户豁免</b>，仅当 {@code ownerId} 等于当前提交者时生效——
 *       用于「某位厨师的昵称就叫『黄焖鸡』」这类个案申诉，而不是把词对全站开放。</li>
 * </ul>
 *
 * <p><b>与词库的关系</b>：白名单<b>只做减法</b>（把已命中的词改成放行），<b>不做加法</b>
 * （不会让未命中的文本被拦截）。因此白名单的误配最坏结果是漏放，
 * 不会造成新的误杀——这让它可以安全地开放给运营自助维护。</p>
 *
 * <p><b>豁免仍然留痕</b>：被白名单放行的命中依然返回
 * {@link ContentVerdict#matchedWord()}（{@link ContentVerdict#whitelisted()} 为真），
 * 用于统计「哪些词命中最多但总被豁免」——那正是词库该收敛的信号。</p>
 *
 * @param id       主键
 * @param word     被豁免的词（与词库中的存储形态一致，匹配时按归一化后的形态比）
 * @param scene    生效场景；{@value #SCENE_ANY} 表示全部
 * @param scope    生效主体范围：{@value #SCOPE_GLOBAL} / {@value #SCOPE_USER}
 * @param ownerId  {@code scope = USER} 时的用户 ID；{@code GLOBAL} 时为 0
 * @param reason   豁免原因（运营留痕，便于回溯「这个词为什么放行」）
 * @param enabled  是否启用（软删除用，保留审计）
 * @param revision 修订号，每次写操作 +1（变更检测用）
 */
public record WhitelistEntry(
        long id,
        String word,
        String scene,
        String scope,
        long ownerId,
        String reason,
        boolean enabled,
        long revision) {

    /** 场景通配：对所有 {@link ContentScene} 生效。 */
    public static final String SCENE_ANY = "*";

    /** 全局豁免：对所有人生效。 */
    public static final String SCOPE_GLOBAL = "GLOBAL";

    /** 用户级豁免：仅对 {@link #ownerId} 本人生效。 */
    public static final String SCOPE_USER = "USER";

    /**
     * 条目是否覆盖「该场景 + 该用户」下的一次命中。
     *
     * <p><b>默认拒绝</b>：{@code scene} / {@code scope} 出现无法识别的取值时返回 {@code false}
     * （即不豁免）。这一侧必须 fail-closed——若默认放行，一次手滑写错 {@code scope}
     * 就会静默关掉整个词的拦截，且没有任何报警会告诉你。</p>
     */
    public boolean covers(ContentScene target, long userId) {
        if (!SCENE_ANY.equals(scene) && (target == null || !target.name().equals(scene))) {
            return false;
        }
        if (SCOPE_GLOBAL.equals(scope)) {
            return true;
        }
        return SCOPE_USER.equals(scope) && ownerId == userId;
    }
}
