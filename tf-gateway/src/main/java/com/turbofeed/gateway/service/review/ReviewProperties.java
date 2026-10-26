package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.service.penalty.ViolationCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 审核配置（turbofeed.media.review.*）。
 *
 * <p>UGC 内容必须审核。默认 {@code auto-pass=false} 表示机审桩（AutoPassModeration）
 * 不自动放行，上传后停在 PENDING，由管理员审核接口（{@code /api/admin/media/{mediaId}/review}）
 * 放行/驳回——即「真审核」闸。设为 {@code true} 退回演示占位（机审直接过），仅供本地联调
 * / 克隆即跑；生产务必关闭，否则 UGC 内容裸奔涉政涉黄。</p>
 *
 * <p><b>为什么从 {@code MediaProperties} 里独立出来（M1 / 3.5）</b>：它原本是
 * {@code MediaProperties} 的一个嵌套类，636 行、34 个字段，把整个配置类撑到 1300+ 行。
 * 更关键的是<b>方向反了</b>——{@code config} 包为了它反过来 import 了
 * {@code service.review} 与 {@code service.penalty} 的类型（ModerationMode、
 * ViolationCategory…），配置类依赖业务包。独立后本类与它服务的审核实现同包，
 * 依赖方向自然；{@code config} 包也不再需要认识任何审核领域类型。</p>
 *
 * <p><b>配置键不变</b>：prefix 仍是 {@code turbofeed.media.review}，因此线上 yml /
 * ConfigMap / 运维脚本<b>无需任何改动</b>——变的是 Java 侧的归属，不是对外契约。
 * 这也是「独立成类」优于「再嵌套一层子域」的原因：后者会把所有键改成
 * {@code turbofeed.media.review.xxx.yyy}，纯属制造 break。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.media.review")
public class ReviewProperties {
    /** 机审自动放行（true=占位直接过；false=人工审核闸，停在 PENDING）。默认 false。 */
    private boolean autoPass = false;

    /**
     * 三档风险机审（#8）：是否启用「按账号信号算风险分、达 MED/HIGH 送人工复审」路由。默认 true。
     *
     * <p><b>为什么是第二道闸</b>：内容安全（caption/图片）已在 presign 经 RULE 机审通过；此处是
     * 基于<b>投稿者</b>信号（账号健康分 / 信用等级 / 历史违规 / 图片分类置信）的<b>风险分层</b>，
     * 与抖音「高优作者 vs 风险作者区别审」一致——风险作者即使内容「看着没问题」也多一道人审兜底。</p>
     *
     * <p>风险分归 [0,1]：低于 {@link #riskLowThreshold} 免审（LOW）；达到 MED 进 RISK_TIERED（tierCode=1）；
     * 达到/超过 {@link #riskMedThreshold} 视为 HIGH（tierCode=2，优先复审）。整条链 fail-open：
     * 读信号/算分失败默认按 MED 送审，绝不阻断上传主流程。</p>
     */
    private boolean riskEnabled = true;

    /**
     * 风险分落 LOW 的上界（归一 [0,1]）：低于此值免人工复审。默认 0.3。
     * 例：健康分高、信用好、无违规的作者风险分低，正常免审放行进 PENDING 排队。
     */
    private double riskLowThreshold = 0.3;

    /**
     * 风险分落 MED 的上界（归一 [0,1]）：达到此值但未达 HIGH 视为 MED（RISK_TIERED，tierCode=1）。默认 0.7。
     * 达到/超过此值视为 HIGH（tierCode=2，优先复审）。阈值外部化，调参不发黑。
     */
    private double riskMedThreshold = 0.7;

    /**
     * 机审策略（可插拔开关）：决定激活哪个 {@link ContentModeration} 实现。
     * 默认 RULE（本地规则引擎，文件名/元数据级 fail-closed，非 AI 视觉）；
     * 视觉语义识别留 CLOUD 接入点（需用户提供云内容安全 API key 后接入）。
     * PASS=占位桩（恒 APPROVED）；AI=Ollama 本地视觉模型（已卸载，保留为接入点）。
     */
    private ModerationMode moderationMode = ModerationMode.RULE;

    /** 机审初筛违禁词表（RULE 引擎 fail-closed 命中即拦；与 CLOUD 视觉语义互补）。 */
    private List<String> bannedKeywords = List.of();

    /**
     * 机审置信度阈值（feature-match M1，moderation-design §P0-1）：机审裁定置信度低于该值，
     * 或显式要求人审（{@code needHumanScan}），则<b>强制送人审</b>（先审后放），不论账号信用等级。
     * 二值实现返回 {@code confidence=1.0} 不受影响；仅启用真实梯度机审（如云内容安全 API）后才生效。默认 0.9。
     */
    private double confidenceThreshold = 0.9;

    /**
     * 新人观察期解除阈值（{@code turbofeed.media.review.new-user-approve-threshold}）：
     * 新注册账号初始处于「先审后放」观察期，累计<b>人工审核通过</b>达到该帖数后自动转正常分级。
     * 只统计人工通过——先发后审的自动通过不计入，否则新号第一帖就会自己转正。
     */
    private int newUserApproveThreshold = 3;

    /** 加严队列持续天数（P0-5）：strict_queue_flag 超此窗口自动降级，避免永久加严。默认 30 */
    private int strictQueueWindowDays = 30;
    /** 新人观察期天数（P0-6）：new_user_watch 超此窗口即使人审通过数未达阈值也自动转正。默认 7 */
    private int newUserWatchWindowDays = 7;
    /** 信用分自然恢复步长（P0-5 增强）：每日凌晨给近 creditRecoverQuietDays 天无违规账号加分。默认 5 */
    private int creditRecoverStep = 5;
    /** 信用分自然恢复静默天数（P0-5 增强）：近 N 天有违规则不恢复。默认 30 */
    private int creditRecoverQuietDays = 30;

    /**
     * 临时封禁触发阈值（penalty 包骨架）：累计违规次数达到此值即临时封禁。默认 3
     * （骨架版按「累计总数」判断；抖音式“同类 N 次”需改按类目计数）。
     */
    private int banTempThreshold = 3;

    /** 临时封禁时长（天，penalty 包骨架）：到期后 {@code ban_until} 过期即自动恢复可写。默认 7 */
    private int banTempDays = 7;

    /**
     * 严重违规（{@code ViolationSeverity#CRITICAL}）是否直接<b>永久</b>封禁。
     *
     * <p><b>默认 false（灰度安全值）</b>：true 时一次 CRITICAL 判定即永久封号——不可逆，
     * 上线初期应先 false，让最重处置也只是临时封禁 {@code banTempDays} 天，
     * 观察误封率与申诉量稳定后再打开（配合 {@link PenaltyService#liftPenalty} 撤销通道）。</p>
     */
    private boolean criticalAutoPermBan = false;

    /**
     * 举报累计复审阈值（抖音式「举报累计 → 人工复核」，changelog 0065）：
     * 同一内容（media_id）的<b>待处理</b>举报数达到该值，且内容仍处于已发布态，
     * 自动建一条 PENDING 复审任务归 REVIEWER 二次研判。默认 3。
     *
     * <p>设计取舍：单条举报不立即拉人工（避免误举报刷爆审核台），累计达阈值才升级，
     * 与抖音「举报量触发人工复核」一致；阈值外部化，调参不发黑。</p>
     */
    private int reportReReviewThreshold = 3;

    /**
     * 热度阈值复审阈值（抖音式「越火审得越严」，changelog 0066）：
     * 同一内容的正向互动（点赞+评论+转发）经 Redis 累计达到该值，且内容仍处于已发布态，
     * 自动建一条 PENDING 复审任务（{@code HEAT_ACCUMULATED}）归 REVIEWER 二次研判。默认 1000。
     *
     * <p>设计取舍：与「举报累计」同构——单个赞/评论不立即拉人工，累计达阈值才升级，
     * 与抖音「越火的内容审得越严」一致；阈值外部化（{@code turbofeed.media.review.heat-re-review-threshold}），调参不发黑。</p>
     */
    private int heatReReviewThreshold = 1000;

    /**
     * 流量池分级热度复审阈值（抖音式「流量池分级」第3档，changelog 0067）：
     * 内容晋级到更高池后，其热度复审阈值按池级<b>收紧</b>——池越高、阈值越低（越火审得越严）。
     * 默认 L1=1000 / L2=500 / L3=200（与全局热度阈值 {@link #heatReReviewThreshold} 同量级、随池递减）。
     * 写入每帖覆盖键 {@code tf:media:heat-threshold:{postId}}，{@code MediaReviewService#onInteraction}
     * 累计热度时优先读该覆盖值，使更高池更快触发 {@code HEAT_ACCUMULATED} 复审。
     */
    private int poolHeatThresholdL1 = 1000;
    private int poolHeatThresholdL2 = 500;
    private int poolHeatThresholdL3 = 200;

    /**
     * 梯度处置矩阵（抖音式「梯度处置」，changelog 待补）：按 "来源:严重度" → 处置名
     * (INTERCEPT/MONITOR/REVIEW/PASS) 覆盖默认处置。缺省按内置默认
     * （HIGH/CRITICAL/MID→INTERCEPT，LOW→MONITOR）。
     * 例：{@code POOL_PROMOTED:HIGH=MONITOR} 把流量池晋级复扫命中改软处置（限公域而非下架）。
     */
    private Map<String, String> dispositionMatrix = new java.util.LinkedHashMap<>();

    /**
     * 账号健康分扣分点（P0-b）：确认一次违规按严重度扣的分。默认值对齐抖音式「先流量隔离」梯度。
     * 一处 CRITICAL(30) 即掉到降权区；两次 HIGH(20) 触限投稿；等。clamp 0~100。
     */
    private int healthDeductLow = 5;
    private int healthDeductMid = 10;
    private int healthDeductHigh = 20;
    private int healthDeductCritical = 30;

    /**
     * 举报人信用下限（P1 #131）：信用低于此值的举报人，其举报<b>不计入</b>复审升级
     * （即无法单独刷起人工复审台）。无信用记录的举报人按满分 100（无罪推定）处理。默认 50。
     */
    private int reporterCredibilityFloor = 50;

    /** 每举报人每小时最大举报数（P1 #131 恶意举报防御②：窗口频控，Redis 固定窗口 fail-open）。默认 30 */
    private int reporterRateLimitPerHour = 30;

    /** 举报频控窗口（秒，P1 #131）。默认 3600（1 小时）。 */
    private int reporterRateLimitWindowSeconds = 3600;

    /** 累计被驳回达此值后，每再被驳回一次额外扣健康分（P1 #131 恶意举报惩罚，复用 P0-b 健康分通道）。默认 5 */
    private int reporterAbuseRejectedThreshold = 5;

    /** 举报被确认成立时信用加分步长（P1 #131，clamp 0~100）。默认 5 */
    private int reporterCredibilityUpStep = 5;

    /** 举报被驳回时信用扣分步长（P1 #131，clamp 0~100）。默认 10 */
    private int reporterCredibilityDownStep = 10;

    /**
     * 举报水位窗口（秒，P1 #132 动态阈值）：近窗口举报总量计数键的过期时长。默认 3600（1 小时）。
     * 窗口内持续有举报则键续期，无举报后自然清零（水位回落）。
     */
    private int waterLevelWindowSeconds = 3600;

    /**
     * 举报水位「平静」上界（P1 #132）：近窗口举报总量 ≤ 此值视为平静 → 阈值放宽（乘数 1.5）。默认 50。
     */
    private int waterLevelCalm = 50;

    /**
     * 举报水位「繁忙」上界（P1 #132）：calm &lt; 总量 ≤ 此值视为正常（乘数 1.0）；
     * 再高至 surge 视为繁忙（收紧 0.7）。默认 200。
     */
    private int waterLevelBusy = 200;

    /**
     * 举报水位「峰涌」上界（P1 #132）：busy &lt; 总量 ≤ 此值视为繁忙（乘数 0.7）；
     * 再高视为峰涌（大幅收紧 0.4）。默认 500。
     */
    private int waterLevelSurge = 500;

    /**
     * 动态复审升级阈值下限（P1 #132）：clamp 下界，防水位峰涌时阈值压到 1 导致单举报即爆审核台。默认 2。
     */
    private int reportThresholdMin = 2;

    /**
     * 动态复审升级阈值上限（P1 #132）：clamp 上界，防水位长期平静时阈值无限放宽。默认 10。
     */
    private int reportThresholdMax = 10;

    public int getNewUserApproveThreshold() {
        return newUserApproveThreshold;
    }

    public void setNewUserApproveThreshold(int newUserApproveThreshold) {
        this.newUserApproveThreshold = newUserApproveThreshold;
    }

    public int getStrictQueueWindowDays() {
        return strictQueueWindowDays;
    }

    public void setStrictQueueWindowDays(int strictQueueWindowDays) {
        this.strictQueueWindowDays = strictQueueWindowDays;
    }

    public int getNewUserWatchWindowDays() {
        return newUserWatchWindowDays;
    }

    public void setNewUserWatchWindowDays(int newUserWatchWindowDays) {
        this.newUserWatchWindowDays = newUserWatchWindowDays;
    }

    public int getCreditRecoverStep() {
        return creditRecoverStep;
    }

    public void setCreditRecoverStep(int creditRecoverStep) {
        this.creditRecoverStep = creditRecoverStep;
    }

    public int getCreditRecoverQuietDays() {
        return creditRecoverQuietDays;
    }

    public void setCreditRecoverQuietDays(int creditRecoverQuietDays) {
        this.creditRecoverQuietDays = creditRecoverQuietDays;
    }

    public int getBanTempThreshold() {
        return banTempThreshold;
    }

    public void setBanTempThreshold(int banTempThreshold) {
        this.banTempThreshold = banTempThreshold;
    }

    public int getBanTempDays() {
        return banTempDays;
    }

    public void setBanTempDays(int banTempDays) {
        this.banTempDays = banTempDays;
    }

    public boolean isCriticalAutoPermBan() {
        return criticalAutoPermBan;
    }

    public void setCriticalAutoPermBan(boolean criticalAutoPermBan) {
        this.criticalAutoPermBan = criticalAutoPermBan;
    }

    public int getReportReReviewThreshold() {
        return reportReReviewThreshold;
    }

    public void setReportReReviewThreshold(int reportReReviewThreshold) {
        this.reportReReviewThreshold = reportReReviewThreshold;
    }

    public int getHeatReReviewThreshold() {
        return heatReReviewThreshold;
    }

    public void setHeatReReviewThreshold(int heatReReviewThreshold) {
        this.heatReReviewThreshold = heatReReviewThreshold;
    }

    public int getPoolHeatThresholdL1() {
        return poolHeatThresholdL1;
    }

    public void setPoolHeatThresholdL1(int poolHeatThresholdL1) {
        this.poolHeatThresholdL1 = poolHeatThresholdL1;
    }

    public int getPoolHeatThresholdL2() {
        return poolHeatThresholdL2;
    }

    public void setPoolHeatThresholdL2(int poolHeatThresholdL2) {
        this.poolHeatThresholdL2 = poolHeatThresholdL2;
    }

    public int getPoolHeatThresholdL3() {
        return poolHeatThresholdL3;
    }

    public void setPoolHeatThresholdL3(int poolHeatThresholdL3) {
        this.poolHeatThresholdL3 = poolHeatThresholdL3;
    }

    public Map<String, String> getDispositionMatrix() {
        return dispositionMatrix;
    }

    public void setDispositionMatrix(Map<String, String> dispositionMatrix) {
        this.dispositionMatrix = dispositionMatrix;
    }

    public int getHealthDeductLow() {
        return healthDeductLow;
    }

    public void setHealthDeductLow(int healthDeductLow) {
        this.healthDeductLow = healthDeductLow;
    }

    public int getHealthDeductMid() {
        return healthDeductMid;
    }

    public void setHealthDeductMid(int healthDeductMid) {
        this.healthDeductMid = healthDeductMid;
    }

    public int getHealthDeductHigh() {
        return healthDeductHigh;
    }

    public void setHealthDeductHigh(int healthDeductHigh) {
        this.healthDeductHigh = healthDeductHigh;
    }

    public int getHealthDeductCritical() {
        return healthDeductCritical;
    }

    public void setHealthDeductCritical(int healthDeductCritical) {
        this.healthDeductCritical = healthDeductCritical;
    }

    public int getReporterCredibilityFloor() {
        return reporterCredibilityFloor;
    }

    public void setReporterCredibilityFloor(int reporterCredibilityFloor) {
        this.reporterCredibilityFloor = reporterCredibilityFloor;
    }

    public int getReporterRateLimitPerHour() {
        return reporterRateLimitPerHour;
    }

    public void setReporterRateLimitPerHour(int reporterRateLimitPerHour) {
        this.reporterRateLimitPerHour = reporterRateLimitPerHour;
    }

    public int getReporterRateLimitWindowSeconds() {
        return reporterRateLimitWindowSeconds;
    }

    public void setReporterRateLimitWindowSeconds(int reporterRateLimitWindowSeconds) {
        this.reporterRateLimitWindowSeconds = reporterRateLimitWindowSeconds;
    }

    public int getReporterAbuseRejectedThreshold() {
        return reporterAbuseRejectedThreshold;
    }

    public void setReporterAbuseRejectedThreshold(int reporterAbuseRejectedThreshold) {
        this.reporterAbuseRejectedThreshold = reporterAbuseRejectedThreshold;
    }

    public int getReporterCredibilityUpStep() {
        return reporterCredibilityUpStep;
    }

    public void setReporterCredibilityUpStep(int reporterCredibilityUpStep) {
        this.reporterCredibilityUpStep = reporterCredibilityUpStep;
    }

    public int getReporterCredibilityDownStep() {
        return reporterCredibilityDownStep;
    }

    public void setReporterCredibilityDownStep(int reporterCredibilityDownStep) {
        this.reporterCredibilityDownStep = reporterCredibilityDownStep;
    }

    public int getWaterLevelWindowSeconds() {
        return waterLevelWindowSeconds;
    }

    public void setWaterLevelWindowSeconds(int waterLevelWindowSeconds) {
        this.waterLevelWindowSeconds = waterLevelWindowSeconds;
    }

    public int getWaterLevelCalm() {
        return waterLevelCalm;
    }

    public void setWaterLevelCalm(int waterLevelCalm) {
        this.waterLevelCalm = waterLevelCalm;
    }

    public int getWaterLevelBusy() {
        return waterLevelBusy;
    }

    public void setWaterLevelBusy(int waterLevelBusy) {
        this.waterLevelBusy = waterLevelBusy;
    }

    public int getWaterLevelSurge() {
        return waterLevelSurge;
    }

    public void setWaterLevelSurge(int waterLevelSurge) {
        this.waterLevelSurge = waterLevelSurge;
    }

    public int getReportThresholdMin() {
        return reportThresholdMin;
    }

    public void setReportThresholdMin(int reportThresholdMin) {
        this.reportThresholdMin = reportThresholdMin;
    }

    public int getReportThresholdMax() {
        return reportThresholdMax;
    }

    public void setReportThresholdMax(int reportThresholdMax) {
        this.reportThresholdMax = reportThresholdMax;
    }

    public boolean isAutoPass() {
        return autoPass;
    }

    public void setAutoPass(boolean autoPass) {
        this.autoPass = autoPass;
    }

    public boolean isRiskEnabled() {
        return riskEnabled;
    }

    public void setRiskEnabled(boolean riskEnabled) {
        this.riskEnabled = riskEnabled;
    }

    public double getRiskLowThreshold() {
        return riskLowThreshold;
    }

    public void setRiskLowThreshold(double riskLowThreshold) {
        this.riskLowThreshold = riskLowThreshold;
    }

    public double getRiskMedThreshold() {
        return riskMedThreshold;
    }

    public void setRiskMedThreshold(double riskMedThreshold) {
        this.riskMedThreshold = riskMedThreshold;
    }

    public ModerationMode getModerationMode() {
        return moderationMode;
    }

    public void setModerationMode(ModerationMode moderationMode) {
        this.moderationMode = moderationMode;
    }

    public List<String> getBannedKeywords() {
        return bannedKeywords;
    }

    public void setBannedKeywords(List<String> bannedKeywords) {
        this.bannedKeywords = bannedKeywords;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    /** 云内容安全 API 配置（feature-match M2，moderation-mode=cloud）。凭据经环境变量注入本地配置，不进仓库。 */
    private Cloud cloud = new Cloud();

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud;
    }

    /** 双严格度策略配置（feature-match M3）：按来源/场景/类目查表得到阈值档案。默认空 → 回落 STANDARD(=confidence-threshold)。 */
    private ModerationPolicyConfig moderationPolicy = new ModerationPolicyConfig();

    public ModerationPolicyConfig getModerationPolicy() {
        return moderationPolicy;
    }

    public void setModerationPolicy(ModerationPolicyConfig moderationPolicy) {
        this.moderationPolicy = moderationPolicy;
    }

    /**
     * 云内容安全 API（视觉语义初审）配置（feature-match M2，通用适配层）。
     *
     * <p>不绑定具体厂商：端点/密钥经环境变量在本地配置（application-local.yml）注入，
     * 仓库默认空串；未配置且非 simulate 时 {@code CloudContentModeration} fail-closed 转人审。
     * 厂商特化响应解析留 TODO（实现 {@code CloudResponseParser}）。</p>
     */
    public static class Cloud {
        /** 模拟模式：不调真实 API，用内置样例响应跑通「响应→ModerationVerdict」映射，用于无 key 环境验证。默认 false。 */
        private boolean simulate = false;
        /** 云内容安全 API 端点（如 https://green-api.aliyun.com/.../image/scan）。默认空。 */
        private String endpoint = "";
        /** 访问 Key（环境变量注入，不进仓库）。默认空。 */
        private String accessKey = "";
        /** 访问 Secret（环境变量注入，不进仓库）。默认空。 */
        private String accessSecret = "";
        /** 请求超时（毫秒）。默认 5000。 */
        private int timeoutMs = 5000;

        public boolean isSimulate() {
            return simulate;
        }

        public void setSimulate(boolean simulate) {
            this.simulate = simulate;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getAccessSecret() {
            return accessSecret;
        }

        public void setAccessSecret(String accessSecret) {
            this.accessSecret = accessSecret;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * 双严格度策略配置（feature-match M3）。
     *
     * <p>查表优先级：类目(byCategory) &gt; 场景(byScenario) &gt; 来源(bySource) &gt; defaultStrictness。
     * 解析出的严格度 → {@code profiles} 取阈值档案；未配置 profiles 时回落 STANDARD
     * （passThreshold=全局 {@code confidence-threshold}）。</p>
     *
     * <p>示例（不进仓库，本地配置）：
     * <pre>
     * turbofeed.media.review.moderation-policy:
     *   default-strictness: STANDARD
     *   by-source:   { RULE: RELAXED, AI: STANDARD, CLOUD: STRICT }
     *   by-scenario: { PUBLIC_FEED: STRICT, PRIVATE: RELAXED }
     *   by-category: { CONTENT_POLITICS: STRICT, CONTENT_PORN: STRICT }
     *   profiles:
     *     STRICT:   { pass-threshold: 0.95 }
     *     STANDARD: { pass-threshold: 0.90 }
     *     RELAXED:  { pass-threshold: 0.70 }
     * </pre>
     * </p>
     */
    public static class ModerationPolicyConfig {
        private ModerationStrictness defaultStrictness = ModerationStrictness.STANDARD;
        private Map<ModerationMode, ModerationStrictness> bySource = new java.util.HashMap<>();
        private Map<ModerationScenario, ModerationStrictness> byScenario = new java.util.HashMap<>();
        private Map<ViolationCategory, ModerationStrictness> byCategory = new java.util.HashMap<>();
        private Map<ModerationStrictness, StrictnessProfile> profiles = new java.util.HashMap<>();

        public ModerationStrictness getDefaultStrictness() {
            return defaultStrictness;
        }

        public void setDefaultStrictness(ModerationStrictness defaultStrictness) {
            this.defaultStrictness = defaultStrictness;
        }

        public Map<ModerationMode, ModerationStrictness> getBySource() {
            return bySource;
        }

        public void setBySource(Map<ModerationMode, ModerationStrictness> bySource) {
            this.bySource = bySource;
        }

        public Map<ModerationScenario, ModerationStrictness> getByScenario() {
            return byScenario;
        }

        public void setByScenario(Map<ModerationScenario, ModerationStrictness> byScenario) {
            this.byScenario = byScenario;
        }

        public Map<ViolationCategory, ModerationStrictness> getByCategory() {
            return byCategory;
        }

        public void setByCategory(Map<ViolationCategory, ModerationStrictness> byCategory) {
            this.byCategory = byCategory;
        }

        public Map<ModerationStrictness, StrictnessProfile> getProfiles() {
            return profiles;
        }

        public void setProfiles(Map<ModerationStrictness, StrictnessProfile> profiles) {
            this.profiles = profiles;
        }
    }
}
