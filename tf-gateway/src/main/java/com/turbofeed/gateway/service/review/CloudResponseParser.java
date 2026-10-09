package com.turbofeed.gateway.service.review;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 云内容安全 API 响应解析器（feature-match M2，通用适配层）。
 *
 * <p>把厂商返回的 JSON 映射为 {@link ModerationVerdict}（决策/置信度/标签/是否强制人审）。
 * 不同厂商响应结构不同，故抽象为接口；{@link GenericCloudResponseParser} 提供通用映射，
 * 厂商特化实现（阿里云绿网 / 腾讯天御 字段名差异）按需另写并覆盖（TODO）。</p>
 */
public interface CloudResponseParser {

    /**
     * 解析云审核响应。
     *
     * @param root    厂商响应根节点（已解析为 JsonNode）
     * @param mediaId 内容标识（日志/错误定位用）
     * @param userId  归属用户
     * @return 带置信度梯度的裁定
     * @throws Exception 解析失败（交由调用方 fail-closed 转人审）
     */
    ModerationVerdict parse(JsonNode root, String mediaId, long userId) throws Exception;
}
