package com.turbofeed.gateway.service.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容安全配置（{@code turbofeed.content-security.*}）。
 *
 * <p>刻意<b>独立于 {@code turbofeed.media.*}</b>：昵称、评论都不是「媒体」的事，
 * 挂在 media 下会让后来者以为只有上传才需要检测。</p>
 *
 * <p>所有阈值与策略都配置化（改策略不发版），但<b>不放任何具体词条</b>——
 * 词条是运营数据，属于数据库；配置文件里出现具体词就是本项目明令禁止的
 * 「demo 字面量进默认配置」。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.content-security")
public class ContentSecurityProperties {

    /** 总开关。关闭后检测直接放行（保留此开关是为了能做「怀疑是本模块误杀」的快速回滚）。 */
    private boolean enabled = true;

    /**
     * 启动时若词库为空，是否直接启动失败。
     *
     * <p><b>默认 false 是刻意的取舍</b>：本地开发与自动化测试常常不需要词库，
     * 强制失败会让「克隆即跑」进一步变难（本项目已因雪花标识必须给值而要求 3 个环境变量）。
     * 但空词库意味着<b>过滤整体失效</b>，所以这里不是「不管」，而是降级为
     * <b>启动 WARN + 指标暴露</b>；生产部署应把本开关置 true，把「忘了导词库」挡在部署阶段。</p>
     */
    private boolean failStartupOnEmptyDictionary = false;

    /** 未单独配置长度的场景使用的兜底上限（字符数）。同时限制 AC 扫描成本，防超长文本拖慢写路径。 */
    private int maxTextLength = 4096;

    /** 各场景的最大长度（字符数）。 */
    private Map<ContentScene, Integer> sceneMaxLength = defaultSceneMaxLength();

    /** 归一化开关（②预处理层）。 */
    private Normalize normalize = new Normalize();

    /** 是否启用白名单（误杀治理的出口）。 */
    private boolean whitelistEnabled = true;

    /**
     * 分类 → 处置动作。未列出的分类走 {@link #defaultAction}。
     *
     * <p>默认<b>留空</b>，即所有分类统一走 {@link ModerationAction#BLOCK}——
     * 与改造前的行为一致（命中即拒），避免上线即改变既有策略。
     * 运营在明确「广告类只降权不拦截」这类策略后，再按分类逐条配置。</p>
     */
    private Map<String, ModerationAction> categoryActions = new LinkedHashMap<>();

    /** 未在 {@link #categoryActions} 中列出的分类的处置动作。 */
    private ModerationAction defaultAction = ModerationAction.BLOCK;

    private static Map<ContentScene, Integer> defaultSceneMaxLength() {
        Map<ContentScene, Integer> m = new EnumMap<>(ContentScene.class);
        // 昵称 32：抖音昵称上限 24 字符，留出余量；昵称是全站公开展示位，短且必须严
        m.put(ContentScene.NICKNAME, 32);
        // caption 2048：与 media 表 caption 列 VARCHAR(2048) 对齐，
        // 否则超长会在 DB 严格模式下抛 SQL 异常（表现为 500 级错误）而不是友好的参数错误
        m.put(ContentScene.CAPTION, 2048);
        // 评论 1024：与 CommentService.MAX_CONTENT_LEN 对齐
        m.put(ContentScene.COMMENT, 1024);
        return m;
    }

    /** 取场景长度上限：未配置则回落到 {@link #maxTextLength}。 */
    public int maxLengthOf(ContentScene scene) {
        Integer v = sceneMaxLength.get(scene);
        return v != null && v > 0 ? v : maxTextLength;
    }

    /** 取分类对应的处置动作：未配置则回落到 {@link #defaultAction}。 */
    public ModerationAction actionOf(String category) {
        if (category == null) {
            return defaultAction;
        }
        ModerationAction a = categoryActions.get(category);
        return a != null ? a : defaultAction;
    }

    /** 归一化配置（②预处理层）。 */
    public static class Normalize {

        /** 归一化总开关。关闭后按原始文本匹配（抗绕过能力随之失效）。 */
        private boolean enabled = true;

        /** 是否剥离分隔符与不可见字符（抗 `涉-黄`、`涉\u200b黄` 这类穿插绕过）。 */
        private boolean stripSeparators = true;

        /**
         * 是否做常用繁体 → 简体映射（抗 `賭博` 这类绕过）。
         *
         * <p>只覆盖<b>常用字表</b>，不是完整的 OpenCC 转换——完整方案需引入第三方依赖，
         * 本项目「零三方依赖」的定位下需单独批准。覆盖不到的生僻繁体仍可绕过，属已知边界。</p>
         */
        private boolean foldTraditional = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isStripSeparators() {
            return stripSeparators;
        }

        public void setStripSeparators(boolean stripSeparators) {
            this.stripSeparators = stripSeparators;
        }

        public boolean isFoldTraditional() {
            return foldTraditional;
        }

        public void setFoldTraditional(boolean foldTraditional) {
            this.foldTraditional = foldTraditional;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailStartupOnEmptyDictionary() {
        return failStartupOnEmptyDictionary;
    }

    public void setFailStartupOnEmptyDictionary(boolean failStartupOnEmptyDictionary) {
        this.failStartupOnEmptyDictionary = failStartupOnEmptyDictionary;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public Map<ContentScene, Integer> getSceneMaxLength() {
        return sceneMaxLength;
    }

    public void setSceneMaxLength(Map<ContentScene, Integer> sceneMaxLength) {
        this.sceneMaxLength = sceneMaxLength;
    }

    public Normalize getNormalize() {
        return normalize;
    }

    public void setNormalize(Normalize normalize) {
        this.normalize = normalize;
    }

    public boolean isWhitelistEnabled() {
        return whitelistEnabled;
    }

    public void setWhitelistEnabled(boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
    }

    public Map<String, ModerationAction> getCategoryActions() {
        return categoryActions;
    }

    public void setCategoryActions(Map<String, ModerationAction> categoryActions) {
        this.categoryActions = categoryActions;
    }

    public ModerationAction getDefaultAction() {
        return defaultAction;
    }

    public void setDefaultAction(ModerationAction defaultAction) {
        this.defaultAction = defaultAction;
    }
}
