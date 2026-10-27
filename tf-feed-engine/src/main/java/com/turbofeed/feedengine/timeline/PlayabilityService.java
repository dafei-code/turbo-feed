package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.client.ReviewSignalClient;
import com.turbofeed.shared.model.PlayableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放前可放性复校（抖音式「非公平态 5s 刷新」服务端核心）。
 *
 * <p><b>为什么需要这一层</b>：召回阶段 {@code FeedTimelineStore} 已用 {@link ReviewSignalClient}
 * 过滤 INTERCEPT/MONITOR 与低健康分/封禁作者，但那是「预取时刻」的结论。用户滑到播放的那一刻，
 * 内容可能已被异步审核 / 举报复审 / 30s 晋级调度翻成 INTERCEPT（下架），或经分享 / 收藏 /
 * {@code media/mine} 直接打开——召回过滤 ≠ 播放阶段一定有效。本服务在「播放前」再复校一次，
 * 命中不可见则客户端渲染占位 + 5s 跳下一条（不发 WATCH/曝光/点赞以免污染推荐模型）。</p>
 *
 * <p><b>复用而非新写</b>：直接复用 {@link ReviewSignalClient} 的 {@code interceptedContentKeys} /
 * {@code blockedAuthorIds} / {@code authorBlockReason}（已 fail-open 直读 {@code tf:mod:} KV），
 * 零新 Redis 代码；作者身份从 postId 解析（与 {@code FeedTimelineStore#authorIdFromKey} 同一约定），
 * 不新增字段。</p>
 *
 * <p><b>判定口径</b>（与既有语义逐字对齐）：{@code tf:mod:media}=INTERCEPT → 不可见；
 * {@code tf:mod:account}=BANNED 或健康分 &lt; {@code dropBelowScore}(默认60) → 不可见；
 * <b>MONITOR（限公域 / 降权）不触发打断</b>（仍可播，仅排序降权）。</p>
 *
 * <p><b>fail-open</b>：KV 读异常 / 缺失一律返回「可播放」，绝不阻断浏览（最坏漏拦一条，而非整页不可用）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger，不依赖 {@code @Slf4j}。</p>
 */
@Service
public class PlayabilityService {

    private static final Logger log = LoggerFactory.getLogger(PlayabilityService.class);

    private final ReviewSignalClient reviewSignal;

    public PlayabilityService(ReviewSignalClient reviewSignal) {
        this.reviewSignal = reviewSignal;
    }

    /**
     * 批量复校：返回每条 postId 的播放前结论。
     *
     * @param postIds 待复校帖子（通常 1~3 条：当前条 + 预查的下一条）
     * @return 与入参同序的复校结果；空入参返回空列表
     */
    public List<PlayableView> check(List<String> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        // 解析作者集合，批量复校作者维度（一次 KV 多读 vs 逐条）
        Set<String> authorIds = new LinkedHashSet<>();
        for (String pid : postIds) {
            String aid = authorIdFromKey(pid);
            if (aid != null) {
                authorIds.add(aid);
            }
        }
        Set<String> intercepted = reviewSignal.interceptedContentKeys(new LinkedHashSet<>(postIds));
        Set<String> blockedAuthors = reviewSignal.blockedAuthorIds(authorIds);
        List<PlayableView> result = new ArrayList<>(postIds.size());
        for (String pid : postIds) {
            if (intercepted.contains(pid)) {
                result.add(PlayableView.blocked(pid, PlayableView.REASON_INTERCEPT));
                continue;
            }
            String aid = authorIdFromKey(pid);
            if (aid != null && blockedAuthors.contains(aid)) {
                String reason = reviewSignal.authorBlockReason(aid);
                result.add(PlayableView.blocked(pid,
                        reason == null ? PlayableView.REASON_ACCOUNT_LOW_SCORE : reason));
                continue;
            }
            result.add(PlayableView.playable(pid));
        }
        return result;
    }

    /**
     * 从帖身份解析作者 userId：postId 形如 {@code post/{userId}/{uuid}}（历史单图回退 mediaId
     * {@code media/{userId}/{uuid}.ext}），取首段斜杠后的 userId。
     *
     * <p><b>P1-1</b>：本方法原是本类自己抄的一份实现（注释也写着"同源逻辑"），
     * 而"同源"只靠注释保证——约定一变（比如以后 postId 加一段路径）就会一边改一边漏，
     * 表现为「部分内容对作者健康分失明」这种不报错的软故障。现直接委托 {@link TimelineSupport}。</p>
     */
    private static String authorIdFromKey(String pk) {
        return TimelineSupport.authorIdFromKey(pk);
    }
}
