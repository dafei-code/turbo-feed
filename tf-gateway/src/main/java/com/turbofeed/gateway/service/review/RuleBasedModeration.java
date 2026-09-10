package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 机审初筛：本地规则引擎（可插拔实现，对应 {@link ModerationMode#RULE}）。
 *
 * <p><b>定位</b>：抖音式「机器初筛」的本地首道。当前 turbo-feed 上传为纯图片、文件名随机（uuid），
 * 本地无视觉模型，故本实现只做<b>文件名/路径级</b>的硬伤 fail-closed 拦截
 * （命中 {@code turbofeed.media.review.banned-keywords} 即 REJECTED）。</p>
 *
 * <p><b>视觉语义级机审留 {@link ModerationMode#CLOUD} 接入点</b>：图片的涉黄/暴恐/涉政识别需视觉模型，
 * 用户接入云内容安全 API（腾讯云/阿里云）后即一个实现类 + 一行配置就能真拦，审核主流程零改动。</p>
 *
 * <p><b>失败安全</b>：未命中违禁词即返回 {@link MediaStatus#APPROVED}（仅「机审不拦截」），
 * 下游由 {@code MediaReviewService#handleUploaded} 按账号信用分级决定 PENDING（先审后放）
 * 或 APPROVED（先发后审），机审不直接放行到公域。</p>
 */
@Slf4j
@Component
public class RuleBasedModeration implements ContentModeration {

    @Override
    public ModerationMode mode() {
        return ModerationMode.RULE;
    }

    private final List<String> bannedKeywords;

    public RuleBasedModeration(MediaProperties mediaProperties) {
        this.bannedKeywords = mediaProperties.getReview().getBannedKeywords();
    }

    @Override
    public MediaStatus moderate(String mediaId, long userId, String url) {
        String name = fileNameOf(url);
        for (String kw : bannedKeywords) {
            if (kw != null && !kw.isBlank() && name.contains(kw)) {
                // fail-closed：文件名级命中违禁词直接拦截（如用户上传带违规文字的文件名）
                log.warn("机审规则引擎命中违禁词（fail-closed 拦截）: mediaId={}, userId={}, keyword={}", mediaId, userId, kw);
                return MediaStatus.REJECTED;
            }
        }
        // 未命中：机审不拦截，下游按信用分级决定 PENDING（先审后放）或 APPROVED（先发后审）
        return MediaStatus.APPROVED;
    }

    private static String fileNameOf(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
