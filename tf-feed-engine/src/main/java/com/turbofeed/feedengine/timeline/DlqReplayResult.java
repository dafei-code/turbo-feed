package com.turbofeed.feedengine.timeline;

/**
 * 死信重放结果。
 *
 * @param total  本次命中（待重放）的死信条数
 * @param replayed 成功重发回主 topic 的条数
 * @param failed   重发失败（仍留在 DLQ）的条数
 */
public record DlqReplayResult(int total, int replayed, int failed) {
}
