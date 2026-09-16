package com.turbofeed.gateway.service.presign;

/**
 * 上传受理回执（{@code POST /api/media/complete} 返回体，HTTP 202）。
 *
 * <p><b>为什么是受理而不是最终结果</b>：完成阶段的落库与送审已异步化投到
 * {@code uploadFinalizeExecutor}，接口只保证「对象已校验通过、收尾任务已提交」。
 * 客户端凭 {@code mediaId} 轮询 {@code GET /api/media/status?mediaId=...} 获取最终状态
 * （收尾未完成时该接口按既有语义返回 PENDING）。</p>
 *
 * @param postId   帖子 ID
 * @param mediaId  帖代表行的媒体 ID（轮询状态用；取自 {@code seq = 0} 的槽位）
 * @param status   当前状态，此处恒为 {@code PENDING}（UGC 先审后显）
 */
public record UploadAccepted(String postId, String mediaId, String status) {
}
