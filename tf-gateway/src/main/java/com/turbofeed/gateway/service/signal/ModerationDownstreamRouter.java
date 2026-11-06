package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.service.moderation.ContentSecurityProperties;
import com.turbofeed.gateway.service.moderation.ContentVerdict;
import com.turbofeed.gateway.service.moderation.ModerationAction;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 内容级 REVIEW/LIMIT 裁定 → 引擎降权信号路由（抖音式「审核与推荐解耦」的下游接线）。
 *
 * <p><b>职责单一</b>：把 {@link ContentVerdict} 的 {@link ModerationAction#REVIEW} / {@link ModerationAction#LIMIT}
 * 两种「名义分级、需下游承接」的裁定，转成引擎可直读的 {@code tf:mod:media=MONITOR} 降权信号。
 * 与 {@link ModerationSignalService}（纯 Redis 写手）的区别：本类负责<b>策略</b>（何时发、发什么严重度），
 * 后者只负责<b>写</b>。</p>
 *
 * <h3>为什么独立成类（而非内联在 MediaUploadService）</h3>
 * <ul>
 *   <li><b>可测性</b>：{@code MediaUploadService} 构造器 23 参、上传主链极重，无法轻量单测；
 *       把策略抽出来后，本类用 2 个 mock 即可锁死「REVIEW/LIMIT 发 MONITOR、PASS 不发、开关可关」。</li>
 *   <li><b>复用</b>：上传路径（postId 已知）与改描述路径（仅 mediaId，需 {@code findPostId} 反查）
 *       共用同一路由，避免两处各写一遍后漂移（参考 {@code MediaStatusMachine} 的 CAS 流转锁定经验）。</li>
 *   <li><b>fail-open 边界</b>：整段包在 try/catch，任何异常只记日志、绝不抛出——内容安全信号丢失
 *       不影响上传/改描述主流程（引擎侧 miss 回源 MySQL，信号只是加速副本）。</li>
 * </ul>
 *
 * <h3>不发信号的情况</h3>
 * <ul>
 *   <li>{@code PASS}：合规内容，无需降权。</li>
 *   <li>{@code BLOCK}：已在 {@code ContentSecurityService#requireClean} 拦截（抛异常），不会到达此处。</li>
 *   <li>对应开关关闭：回退到旧行为（REVIEW 仅建人审任务、LIMIT 无下游动作），用于灰度与紧急止血。</li>
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
     * 把内容级裁定路由到引擎降权信号。
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
                log.info("内容REVIEW→引擎降权信号(MONITOR): postId={}, severity={}, word={}, category={}",
                        postId, properties.getDefaultReviewSeverity(), verdict.matchedWord(), verdict.category());
            } else if (action == ModerationAction.LIMIT) {
                if (!properties.isLimitPublishSignal()) {
                    return;
                }
                signalService.publishMediaDisposition(postId, Disposition.MONITOR,
                        properties.getDefaultLimitSeverity(), ViolationSource.MACHINE);
                log.info("内容LIMIT→引擎降权信号(MONITOR): postId={}, severity={}, word={}, category={}",
                        postId, properties.getDefaultLimitSeverity(), verdict.matchedWord(), verdict.category());
            }
        } catch (Exception e) {
            // fail-open：信号是加速副本，丢失不影响主流程（引擎 miss 回源 MySQL）
            log.warn("内容REVIEW/LIMIT下游信号发送失败(fail-open，不影响主流程): postId={}, verdict={}, {}",
                    postId, verdict == null ? "null" : verdict.action(), e.getMessage());
        }
    }
}
