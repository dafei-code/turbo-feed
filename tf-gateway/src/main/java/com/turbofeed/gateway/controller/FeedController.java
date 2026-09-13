package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.query.MediaQueryService;
import com.turbofeed.shared.result.Result;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公域推荐流接口（与「个人中心 /api/media/mine」职责分离）。
 *
 * <p><b>职责边界</b>：{@code /api/media/mine} 面向登录用户本人、按 {@code user_id} 分片查询
 * 自己的内容；本控制器面向公域、不依赖用户身份，提供全平台可消费的推荐流。</p>
 *
 * <p><b>数据来源（B1 服务拆分后）</b>：本接口是<b>对外契约的稳定锚点</b>——路径、参数、
 * 响应结构三者均未变化，前端无需任何改动；实现已从"网关内部查库/读本地时间线"改为
 * 转发 tf-feed-engine 的时间线读模型（O(log n)/页，零扫分片库）。
 * 换言之：推荐流的<b>可见性</b>由引擎负责，网关只保留接入与降级决策。</p>
 *
 * <p><b>降级语义</b>：引擎不可用时默认返回空列表并打 WARN（{@code turbofeed.feed.degraded-mode=empty}），
 * 而不是回源广播查全部分片——后者会把一次引擎抖动放大成存储层雪崩。仅本地/演示可切
 * {@code local-scan} 恢复拆分前的回源行为。</p>
 *
 * <p><b>抖音级演进提示</b>：① 推荐流缓存已在引擎侧落短 TTL 旁路缓存；② 下一步是把时间线
 * 投递从同步 HTTP 改为 RocketMQ 可靠投递；③ 内容元数据异构到 ES / 宽表；
 * ④ 媒体 URL 走 CDN，网关不过字节流。</p>
 */
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final MediaQueryService mediaQueryService;

    /**
     * 推荐流（按入流时间倒序返回全平台 APPROVED 内容，分页）。
     *
     * <p>公域 feed 默认允许未登录访问（抖音「推荐」页同样未登录可见），故不强制取用户身份；
     * 若后续需要个性化（关注流、兴趣排序），再叠加身份与推荐参数。</p>
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 单页条数（默认 20，≤0 兜底 20）
     * @return 推荐内容列表（仅 APPROVED），空则无更多内容或引擎不可用（降级）
     */
    @GetMapping("/recommended")
    public Result<List<MediaItem>> recommended(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(mediaQueryService.listRecommended(page, size));
    }
}
