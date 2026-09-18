package com.turbofeed.gateway.service.moderation;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 内容安全统一入口（①接入层）—— 所有文本进入系统的<b>唯一</b>检测入口。
 *
 * <h3>为什么必须是「唯一入口」</h3>
 * 改造前的形态是各业务自己调 {@code sensitiveWordService.requireClean(text)}：
 * 谁想得起来谁调。结果是昵称完全没调（实测缺口）、评论与描述各调各的、
 * 新增一处文案入口时没人记得要加。更隐蔽的是<b>每个人的理解都不一样</b>——
 * 有人以为长度校验在这里，有人以为那里做了归一化。
 * 把入口收敛成一个方法后，「哪些场景被检测」变成一个可枚举的清单
 * （{@link ContentScene}），漏接是<b>看得见</b>的。</p>
 *
 * <h3>一次检测的完整链路</h3>
 * <pre>
 *   调用方（写路径）
 *     ↓ requireClean(scene, text, userId)      ← 写路径入口：不合格即抛异常
 *   ① 长度校验（按场景上限，最便宜的检查放最前）
 *     ↓ check(scene, text, userId)             ← 纯检测入口：返回裁定，不抛异常
 *   ② 归一化（TextNormalizer：NFKC / 剥分隔符 / 繁简 / 小写）
 *   ③ 匹配（AhoCorasick 单次扫描，O(n)）
 *   ④ 决策：白名单豁免？→ 分类查表得动作
 *   ⑤ 计数（命中 / 豁免 / 各动作分场景计数）
 *     ↓
 *   ContentVerdict（动作 + 命中词 + 分类 + 原文位置）
 * </pre>
 *
 * <h3>两个入口的分工</h3>
 * <ul>
 *   <li>{@link #requireClean} —— <b>写路径</b>用。语义是「不合格就拒绝」，抛
 *       {@link BizException}。它<b>包含</b>长度校验，因为长度不合格同样属于「不能发布」。</li>
 *   <li>{@link #check} —— <b>只检测不打断</b>。给运营后台的「预检」、给审计与
 *       批量离线扫描用。它<b>不做</b>长度校验（检测关心的是内容合不合规，
 *       长度是接口参数问题）。</li>
 * </ul>
 *
 * <h3>失败方向</h3>
 * 本层整体 fail-closed：<b>命中即拒</b>（{@link ModerationAction#denies()}），
 * 词库为空则等同于全放行——这是词库的问题，由 {@link SensitiveWordService}
 * 的启动守卫与指标暴露（不在这里假装拦住了）。白名单是整个链路上唯一的
 * fail-open 环节，且方向是<b>只减不增</b>（只能把命中改成放行，不能制造新拦截）。
 *
 * <p><b>无状态</b>：除计数器外不持有可变状态，可安全并发调用；AC 与白名单的快照
 * 分别由 {@link SensitiveWordService} / {@link WhitelistService} 用原子引用维护。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSecurityService {

    private final ContentSecurityProperties properties;
    private final SensitiveWordService sensitiveWordService;
    private final WhitelistService whitelistService;

    /**
     * 计数器：{@code "场景:结果"} → 次数。
     *
     * <p><b>为什么要计数</b>：改造前最致命的问题不是「拦不住」，而是<b>拦不住这件事没人知道</b>——
     * 词库 0 条、Trie 构建成功、接口 200，从任何角度看都是"正常"。
     * 计数器让「命中数」与「豁免数」成为可观测事实：命中恒为 0 且词库非空 = 可能全被绕过；
     * 豁免数远超命中数 = 白名单配得太宽。</p>
     *
     * <p>用 {@link LongAdder} 而非 {@code AtomicLong}：写路径多线程竞争，
     * {@code LongAdder} 在分散热点上明显更优；读取（admin 展示）是低频的。</p>
     */
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** 结果标签：白名单豁免（单独一档，与命中区分）。 */
    private static final String RESULT_WHITELISTED = "WHITELISTED";

    // ==================== 启动自检（S1「有效性可见」的关键守卫） ====================

    /**
     * 启动自检：把「过滤到底有没有在工作」在启动阶段就说清楚。
     *
     * <p><b>为什么这是最重要的一处改动</b>：改造前的形态是——词库表 {@code COUNT(*)=0}、
     * Trie 构建成功、接口全 200，<b>从任何角度看都"正常"</b>，但整套审核实际不拦任何东西，
     * 且没有任何出口能暴露这件事。这类"静默失效"比"拦不住"危险得多：前者会让你以为
     * 自己是安全的。</p>
     *
     * <p>三种情况分别处理：</p>
     * <ul>
     *   <li><b>词库非空</b> → INFO 输出词库/白名单/归一化开关/繁简映射条目数。
     *       繁简映射的<b>非法条目数</b>一并输出（正常为 0）——它是编码自检的外显出口，
     *       历史教训是「手滑漏写一个字导致整张映射表错位且静默运行」。</li>
     *   <li><b>词库为空 + {@code fail-startup-on-empty-dictionary=true}</b> → 抛异常，
     *       <b>拒绝启动</b>。把「忘了导词库」挡在部署阶段，而不是等线上无人发现。</li>
     *   <li><b>词库为空 + 开关为 false（默认）</b> → WARN 降级。默认不拦是为了不破坏
     *       「克隆即跑」（本地开发/自动化测试常常不需要词库），但日志必须刺眼。</li>
     * </ul>
     *
     * <p>自检自身不做 fail-closed：读 DB 失败时只记 WARN。原因是 DB 不通时
     * {@link SensitiveWordService#init()} 已记过 ERROR，这里再抛一次只会让
     * 「连接池瞬时抖动」升级成「进程起不来」——而进程起不来意味着连数据都看不到。</p>
     */
    @PostConstruct
    public void verifyDictionary() {
        int dictSize;
        long dbCount;
        try {
            dictSize = sensitiveWordService.currentSize();
            dbCount = sensitiveWordService.count();
        } catch (Exception e) {
            log.warn("内容安全启动自检无法读取词库（跳过校验，稍后由定时刷新自愈）: {}", e.getMessage());
            return;
        }

        if (dictSize == 0) {
            String msg = "内容安全词库为空：敏感词过滤当前不生效"
                    + "（已加载=" + dictSize + ", 词库表行数=" + dbCount + "）";
            if (properties.isFailStartupOnEmptyDictionary()) {
                throw new IllegalStateException(msg
                        + " —— turbofeed.content-security.fail-startup-on-empty-dictionary=true，拒绝启动");
            }
            log.warn("{} —— 已按配置降级为 WARN；生产部署建议置 "
                    + "fail-startup-on-empty-dictionary=true 以在部署阶段拦下未导词库的实例", msg);
            return;
        }

        ContentSecurityProperties.Normalize n = properties.getNormalize();
        int invalid = TextNormalizer.invalidPairCount();
        int mismatch = TextNormalizer.foldMismatchCount();
        log.info("内容安全启动自检通过: dictionarySize={}, whitelistSize={}, normalize={}(strip{} / trad{}), "
                        + "繁简映射={}条(非法={}, 方向自检失败={}), 默认动作={}",
                dictSize, whitelistService.currentSize(),
                n.isEnabled() ? "on" : "off",
                n.isStripSeparators() ? "开" : "关",
                n.isFoldTraditional() ? "开" : "关",
                TextNormalizer.traditionalMapSize(),
                invalid, mismatch,
                properties.getDefaultAction());
        if (invalid > 0) {
            log.error("繁简映射表存在 {} 条非法条目（应为 0）：这些字形不会被折叠，请修正 "
                            + "TextNormalizer.TRAD_SIMP_PAIRS；样本={}",
                    invalid, TextNormalizer.invalidPairSamples());
        }
        if (mismatch > 0) {
            log.error("繁简映射表方向自检失败 {} 项（应为 0）：可能是「简→繁」写反或高频繁字缺失。"
                            + "前者会让简体文本被转成繁体、导致所有简体词条漏放 —— 立即修正", mismatch);
        }
    }

    // ==================== 写路径入口 ====================

    /**
     * 写路径统一入口：长度 + 敏感词，任一不合格即抛异常。
     *
     * @param scene  检测场景（决定长度上限与合规尺度）
     * @param text   待检测文本；{@code null} / 空串视为通过
     * @param userId 提交者（用于用户级白名单；无用户上下文传 {@code 0}）
     * @throws BizException {@link ErrorCode#PARAM_ERROR} 超长；
     *                      {@link ErrorCode#SENSITIVE_WORD_HIT} 命中且未被豁免
     */
    public void requireClean(ContentScene scene, String text, long userId) {
        requireWithinLimit(scene, text);
        ContentVerdict verdict = check(scene, text, userId);
        if (verdict.denies()) {
            log.warn("内容检测未通过: scene={}, action={}, word={}, category={}, userId={}, hit=[{}, {})",
                    scene, verdict.action(), verdict.matchedWord(), verdict.category(),
                    userId, verdict.hitStart(), verdict.hitEnd());
            throw new BizException(ErrorCode.SENSITIVE_WORD_HIT, denyMessage(verdict));
        }
    }

    /** 无用户上下文的便捷重载（系统内部调用，不参与用户级白名单）。 */
    public void requireClean(ContentScene scene, String text) {
        requireClean(scene, text, 0L);
    }

    /**
     * 长度校验（按场景上限）。
     *
     * <p><b>为什么放在本层而不是各业务自己判</b>：长度上限与场景强相关
     * （昵称 32 / 描述 2048 / 评论 1024），而这些数字必须与数据库列宽、
     * 前端输入框上限保持<b>同一个事实源</b>。散落在三处的结果是：前端卡了 500 字、
     * 服务端没卡、DB 列 2048——超限请求走到 DB 才炸，表现为 500 级错误而不是
     * 友好的参数错误。集中在配置里（{@code turbofeed.content-security.scene-max-length}）
     * 至少保证服务端只有一个数字。</p>
     *
     * <p>顺带限制 AC 扫描成本：超长文本会线性拉长写路径的 P99，先挡住。</p>
     */
    public void requireWithinLimit(ContentScene scene, String text) {
        if (text == null) {
            return;
        }
        int max = properties.maxLengthOf(scene);
        if (text.length() > max) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    sceneLabel(scene) + "过长（≤" + max + " 字符，当前 " + text.length() + " 字符）");
        }
    }

    // ==================== 纯检测入口 ====================

    /**
     * 纯检测：返回裁定，不抛异常、不校验长度。
     *
     * @param scene  检测场景
     * @param text   待检测文本
     * @param userId 提交者（用户级白名单）；无上下文传 {@code 0}
     * @return 裁定结果；未命中或总开关关闭返回 {@link ContentVerdict#pass}
     */
    public ContentVerdict check(ContentScene scene, String text, long userId) {
        if (text == null || text.isEmpty() || !properties.isEnabled()) {
            return ContentVerdict.pass(scene);
        }

        TextNormalizer.Normalized normalized = normalize(text);
        if (normalized.text().isEmpty()) {
            // 整段都是分隔符/不可见字符：没有可匹配的内容，直接放行
            return ContentVerdict.pass(scene);
        }

        SensitiveWordHit hit = sensitiveWordService.match(normalized.text());
        if (hit == null) {
            return ContentVerdict.pass(scene);
        }

        if (properties.isWhitelistEnabled() && whitelistService.isExempt(hit.word(), scene, userId)) {
            count(scene, RESULT_WHITELISTED);
            log.info("敏感词命中但被白名单豁免: scene={}, word={}, category={}, userId={}",
                    scene, hit.word(), hit.category(), userId);
            return ContentVerdict.exempt(scene, hit.word(), hit.category());
        }

        ModerationAction action = properties.actionOf(hit.category());
        count(scene, action.name());

        // 归一化下标 → 原文下标：命中区间是半开区间 [start, end)，
        // 只需回溯首尾两个字符；若首字符回溯失败（理论上不会）则整段标记为「位置未知」
        int start = normalized.toOriginalIndex(hit.start());
        int lastOriginal = normalized.toOriginalIndex(hit.end() - 1);
        int end = (start < 0 || lastOriginal < 0) ? -1 : lastOriginal + 1;

        return new ContentVerdict(action, scene, hit.word(), hit.category(), false,
                end < 0 ? -1 : start, end);
    }

    /** 无用户上下文的便捷重载。 */
    public ContentVerdict check(ContentScene scene, String text) {
        return check(scene, text, 0L);
    }

    // ==================== 可观测性 ====================

    /**
     * 计数器快照（key 形如 {@code "CAPTION:BLOCK"}），按 key 排序便于比对。
     *
     * <p>与 {@link #stats()} 分开：计数器是<b>只增</b>的累计量（可算速率），
     * stats 里的词库大小是<b>瞬时</b>量——两者混在一起会让人误以为词库大小也在累加。</p>
     */
    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> snap = new TreeMap<>();
        counters.forEach((k, v) -> snap.put(k, v.sum()));
        return snap;
    }

    /**
     * 运行态自检快照 —— <b>S1「有效性可见」的核心产物</b>。
     *
     * <p>把「这套过滤现在到底有没有在工作」压缩成一次调用可回答的问题：
     * 开关是否打开、词库加载了几条、白名单几条、归一化开了哪些步骤。
     * 改造前这些信息没有任何出口，导致「词库 0 条、过滤静默失效」可以持续存在
     * 而无人察觉——这个 record 就是为了让那种状态<b>不可能不被发现</b>。</p>
     *
     * @param enabled              内容安全总开关
     * @param dictionarySize       已加载到 AC 的敏感词条数（0 = 过滤失效）
     * @param dictionaryDbCount    词库表总行数（含禁用；与 dictionarySize 差值可暴露加载异常）
     * @param whitelistSize        已加载的白名单条目数
     * @param normalizeEnabled     归一化总开关
     * @param stripSeparators      是否剥离分隔符（抗穿插绕过）
     * @param foldTraditional      是否折叠繁简
     * @param traditionalMapSize   繁简映射表条目数（编码自检的旁证）
     * @param invalidPairCount     繁简映射表的非法条目数（两字相异校验不通过），正常为 0
     * @param foldMismatchCount    繁简映射表的方向自检失败数（把「繁→简」写反会让简体文本被转成繁体、
     *                             <b>所有简体词条漏放</b>），正常为 0
     * @param counters             累计计数快照
     */
    public record Stats(
            boolean enabled,
            int dictionarySize,
            long dictionaryDbCount,
            int whitelistSize,
            boolean normalizeEnabled,
            boolean stripSeparators,
            boolean foldTraditional,
            int traditionalMapSize,
            int invalidPairCount,
            int foldMismatchCount,
            Map<String, Long> counters) {
    }

    /** 采集运行态快照。 */
    public Stats stats() {
        ContentSecurityProperties.Normalize n = properties.getNormalize();
        return new Stats(
                properties.isEnabled(),
                sensitiveWordService.currentSize(),
                sensitiveWordService.count(),
                whitelistService.currentSize(),
                n.isEnabled(),
                n.isStripSeparators(),
                n.isFoldTraditional(),
                TextNormalizer.traditionalMapSize(),
                TextNormalizer.invalidPairCount(),
                TextNormalizer.foldMismatchCount(),
                metricsSnapshot());
    }

    // ==================== 内部 ====================

    /**
     * 按配置做归一化。
     *
     * <p>{@code normalize.enabled=false} 时传 {@code (false, false)}，
     * 但<b>不可见字符仍然会被剥离</b>（{@link TextNormalizer} 内部行为）——
     * 零宽字符从无正语义，保留它只会让「文本看起来正常却匹配不上」变得难以排查。</p>
     */
    private TextNormalizer.Normalized normalize(String text) {
        ContentSecurityProperties.Normalize n = properties.getNormalize();
        if (!n.isEnabled()) {
            return TextNormalizer.normalize(text, false, false);
        }
        return TextNormalizer.normalize(text, n.isStripSeparators(), n.isFoldTraditional());
    }

    private void count(ContentScene scene, String result) {
        counters.computeIfAbsent(scene.name() + ":" + result, k -> new LongAdder()).increment();
    }

    private static String sceneLabel(ContentScene scene) {
        return switch (scene) {
            case NICKNAME -> "昵称";
            case CAPTION -> "描述";
            case COMMENT -> "评论";
        };
    }

    /**
     * 拒绝时的用户可见文案。
     *
     * <p><b>不回显命中的词</b>：回显等于把词库内容送给绕过者——多试几次就能
     * 把词库猜出来（这是对抗型用户的标准做法）。只告知「包含不允许发布的内容」。
     * 命中词只进服务端日志与计数器，供运营核对。</p>
     */
    private static String denyMessage(ContentVerdict verdict) {
        return sceneLabel(verdict.scene()) + "包含不允许发布的内容";
    }
}
