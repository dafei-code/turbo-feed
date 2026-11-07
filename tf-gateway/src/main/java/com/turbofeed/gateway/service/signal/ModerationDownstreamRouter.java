package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.service.moderation.ContentSecurityProperties;
import com.turbofeed.gateway.service.moderation.ContentVerdict;
import com.turbofeed.gateway.service.moderation.ModerationAction;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 内容级 REVIEW/LIMIT 裁定 → 引擎信号路由（抖音式「审核与推荐解耦」的下游接线，ADR-0014）。
 *
 * <p><b>职责单一</b>：把 {@link ContentVerdict} 的 {@link ModerationAction#REVIEW} /
 * {@link ModerationAction#LIMIT} 两种「名义分级、需下游承接」的裁定，按<b>处置力度分档</b>
 * 转成引擎可直读的 {@code tf:mod:media} 信号；并在内容重新合规时按规则解除。
 * 与 {@link ModerationSignalService}（纯 Redis 读写手）的区别：本类负责<b>策略</b>
 * （何时发、发什么档位与严重度、何时解除、何时不覆盖），后者只负责<b>读写</b>。</p>
 *
 * <h3>两档语义（ADR-0013 曾把二者混为一谈，已由 ADR-0014 修正）</h3>
 * <table border="1">
 *   <caption>裁定 → 处置映射</caption>
 *   <tr><th>裁定</th><th>信号</th><th>引擎行为</th><th>解除时机</th></tr>
 *   <tr><td>REVIEW</td><td>MONITOR / MID</td>
 *       <td>召回层与搜索层<b>硬剔除</b>（先发后审期间的公域隔离）</td>
 *       <td>CONTENT_REVIEW 人审判无违规</td></tr>
 *   <tr><td>LIMIT</td><td>DEMOTE / LOW</td>
 *       <td>仅精排乘 {@code contentDemoteScale}；<b>仍可进公域、仍可搜、仍可播放</b></td>
 *       <td>改描述后裁定 PASS</td></tr>
 * </table>
 *
 * <p>LIMIT 是 {@link ModerationAction} 中最轻的非 PASS 档（评论场景只做 FOLDED），
 * 若也套 MONITOR，等于把整帖从公域发现流和搜索结果里抹掉，处置力度与 REVIEW 等同，
 * 明显过重。DEMOTE 档即为此新增，见 {@link Disposition#DEMOTE} 的跨服务消费契约。</p>
 *
 * <h3>为什么独立成类（而非内联在 MediaUploadService）</h3>
 * <ul>
 *   <li><b>可测性</b>：{@code MediaUploadService} 是上传主链极重的门面（含校验链、分片落库、
 *       对象存储、发件箱等二十余个协作 Bean），无法轻量单测；把策略抽出来后，本类用 2 个 mock
 *       即可锁死「REVIEW→MONITOR / LIMIT→DEMOTE / PASS 不发 / 开关可关 / 只升级不降级」。</li>
 *   <li><b>策略集中、入口分离</b>：上传（无历史信号）走 {@link #routeContentVerdict}，
 *       改描述（可能已有更重处置）走 {@link #routeCaptionUpdate} 的「只升级不降级」策略；
 *       二者共用同一份裁定→档位映射与开关，策略只有一处定义，不会各写一遍后漂移
 *       （参考 {@code MediaStatusMachine} 的 CAS 流转锁定经验）。</li>
 *   <li><b>fail-open 边界</b>：两个入口整段包在 try/catch，任何异常只记日志、绝不抛出——
 *       内容安全信号丢失不影响上传/改描述主流程（引擎侧 miss 回源 MySQL，信号只是加速副本）。</li>
 *   <li><b>零构造器变更</b>：改描述的解除逻辑也复用本类作为出口，因此
 *       {@code MediaUploadService} / {@code MediaReviewService} 都无需新增依赖，避免
 *       「测试手动 new 的服务因构造器加参而编译断裂」这类 CI 回归。</li>
 * </ul>
 *
 * <h3>不发信号的情况</h3>
 * <ul>
 *   <li>{@code PASS}：合规内容无需处置；改描述路径则条件解除旧的 DEMOTE 轻降权。</li>
 *   <li>{@code BLOCK}：已在 {@code ContentSecurityService#requireClean} 拦截（抛异常），不会到达此处。</li>
 *   <li>对应开关关闭：回退到旧行为（REVIEW 仅建人审任务、LIMIT 无下游动作），用于灰度与紧急止血。</li>
 *   <li>postId 为空：历史单图帖 {@code post_id=''} 无法形成稳定信号键，宁可漏发不误发。</li>
 * </ul>
 */
@Service
public class ModerationDownstreamRouter {

    /** 显式日志（本机构建环境 Lombok 对新文件不生效，统一手写构造器 + Logger）。 */
    private static final Logger log = LoggerFactory.getLogger(ModerationDownstreamRouter.class);

    private final ContentSecurityProperties properties;
    private final ModerationSignalService signalService;

    public ModerationDownstreamRouter(ContentSecurityProperties properties,
                                     ModerationSignalService signalService) {
        this.properties = properties;
        this.signalService = signalService;
    }

    /**
     * 上传路径：把内容级裁定路由到引擎处置信号（该帖尚无历史信号，直接写）。
     *
     * @param postId  帖子 ID（{@code post/...} 形态）；为空则跳过（信号需要稳定主键，缺则宁可漏发不误发）
     * @param verdict 内容裁定（非空；{@code action} 为 {@code REVIEW}/{@code LIMIT} 才处理）
     */
    public void routeContentVerdict(String postId, ContentVerdict verdict) {
        try {
            if (postId == null || postId.isBlank() || verdict == null) {
                return;
            }
            ModerationAction action = verdict.action();
            if (action == ModerationAction.REVIEW) {
                if (!properties.isReviewPublishSignal()) {
                    return;
                }
                signalService.publishMediaDisposition(postId, Disposition.MONITOR,
                        properties.getDefaultReviewSeverity(), ViolationSource.MACHINE);
                log.info("内容REVIEW→公域隔离信号(MONITOR): postId={}, severity={}, word={}, category={}",
                        postId, properties.getDefaultReviewSeverity(), verdict.matchedWord(), verdict.category());
            } else if (action == ModerationAction.LIMIT) {
                if (!properties.isLimitPublishSignal()) {
                    return;
                }
                signalService.publishMediaDisposition(postId, Disposition.DEMOTE,
                        properties.getDefaultLimitSeverity(), ViolationSource.MACHINE);
                log.info("内容LIMIT→轻降权信号(DEMOTE): postId={}, severity={}, word={}, category={}",
                        postId, properties.getDefaultLimitSeverity(), verdict.matchedWord(), verdict.category());
            }
        } catch (Exception e) {
            // fail-open：信号是加速副本，丢失不影响主流程（引擎 miss 回源 MySQL）
            log.warn("内容REVIEW/LIMIT下游信号发送失败(fail-open，不影响主流程): postId={}, verdict={}, {}",
                    postId, verdict == null ? "null" : verdict.action(), e.getMessage());
        }
    }

    /**
     * 改描述专用策略入口（ADR-0014）：以新文本裁定为准，但<b>只升级不降级</b>。
     *
     * <p>帖上可能已有更重的处置结论，若像上传那样直接覆盖，作者改一次文案就能绕过：</p>
     * <ul>
     *   <li>PASS → 只条件解除 DEMOTE（{@code clearDemotionIfPresent}）；MONITOR / INTERCEPT 保留，
     *       它们是人审隔离 / 举报下架的结论，不属于「文本质量」，只能由对应流程解除；</li>
     *   <li>LIMIT → 目标 DEMOTE，但若现存 MONITOR / INTERCEPT 则<b>不覆盖</b>，避免把人审隔离降级；</li>
     *   <li>REVIEW → 目标 MONITOR（比 DEMOTE 重）可覆盖 DEMOTE；若现存 INTERCEPT 仍保留。</li>
     * </ul>
     *
     * <p><b>不要在自动审核 APPROVED 时解除 MONITOR</b>：先发后审正靠它做审核窗口隔离。
     * 人审判无违规的解除走 {@code ViolationDispositionService#clearContentSignal}。</p>
     *
     * <p>fail-open：读/写信号任何异常只记日志、不抛出，不影响改描述主流程。</p>
     */
    public void routeCaptionUpdate(String postId, ContentVerdict verdict) {
        try {
            if (postId == null || postId.isBlank() || verdict == null) {
                return;
            }
            ModerationAction action = verdict.action();
            if (action == ModerationAction.PASS) {
                signalService.clearDemotionIfPresent(postId);
                return;
            }
            if (action == ModerationAction.BLOCK) {
                // 正常不可达（requireClean 已抛异常）；防御性保留原信号、不做任何变更
                return;
            }
            boolean review = action == ModerationAction.REVIEW;
            if (review ? !properties.isReviewPublishSignal() : !properties.isLimitPublishSignal()) {
                return;
            }
            Disposition target = review ? Disposition.MONITOR : Disposition.DEMOTE;
            // 只升级不降级：现存 MONITOR/INTERCEPT 是更重的处置结论，不得被改描述冲掉
            ModerationSignalView.DispositionView current = signalService.getMediaDisposition(postId);
            if (current != null && ("MONITOR".equals(current.action()) || "INTERCEPT".equals(current.action()))) {
                log.info("改描述保留既有更重处置信号: postId={}, current={}, target={}",
                        postId, current.action(), target);
                return;
            }
            signalService.publishMediaDisposition(postId, target,
                    review ? properties.getDefaultReviewSeverity() : properties.getDefaultLimitSeverity(),
                    ViolationSource.MACHINE);
            log.info("改描述裁定→内容处置信号: postId={}, action={}, target={}", postId, action, target);
        } catch (Exception e) {
            log.warn("改描述内容信号路由失败(fail-open，不影响主流程): postId={}, verdict={}, {}",
                    postId, verdict == null ? "null" : verdict.action(), e.getMessage());
        }
    }
}
