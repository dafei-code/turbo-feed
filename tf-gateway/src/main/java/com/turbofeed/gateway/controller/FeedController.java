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
 * 自己的内容；本控制器面向公域、不依赖用户身份，提供全平台可消费的推荐流。两者数据来源
 * 当前都取自 media 表，但路由与契约已解耦——后续 P3 阶段推荐流将切换到推荐服务 + 异构索引，
 * 本接口实现无需变更即可替换底层（见 docs/changelog/0017-feed-pagination-split.md）。</p>
 *
 * <p><b>抖音级演进提示</b>：当前 {@code recommended} 为<b>占位实现</b>，直接跨分片广播查
 * APPROVED 内容，仅适合演示/小数据量。生产环境须具备：
 * ① Redis 缓存热点 feed，拦截高频轮询；② 推荐服务按推/拉/混合模式预生成收件箱；
 * ③ 内容元数据异构到 ES / 倒排 / 宽表，避免实时扫分片库；④ 媒体 URL 走 CDN，网关不过字节流。</p>
 */
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final MediaQueryService mediaQueryService;

    /**
     * 推荐流（占位实现，按时间倒序返回全平台 APPROVED 内容，分页）。
     *
     * <p>公域 feed 默认允许未登录访问（抖音「推荐」页同样未登录可见），故不强制取用户身份；
     * 若后续需要个性化（关注流、兴趣排序），再叠加身份与推荐参数。</p>
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 单页条数（默认 20，≤0 兜底 20）
     * @return 推荐内容列表（仅 APPROVED），空则无更多内容
     */
    @GetMapping("/recommended")
    public Result<List<MediaItem>> recommended(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(mediaQueryService.listRecommended(page, size));
    }
}
