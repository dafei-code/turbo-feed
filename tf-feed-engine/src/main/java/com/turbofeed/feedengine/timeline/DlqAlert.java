package com.turbofeed.feedengine.timeline;

import java.util.List;

/**
 * 死信积压告警载荷（P0-2 DLQ 告警部署接线）。
 *
 * <p>由 {@link DlqAlertService} 巡检生成、经 {@link DlqNotifier} 发出（日志 + 可选 webhook）。
 * 仅承载"有多少条死信、样例 mediaId、哪个引擎实例"这类运维必需信息，不泄露消息体。</p>
 *
 * @param count     当前 DLQ 积压条数（已达阈值才发）
 * @param samples   样例 mediaId（最多 {@code sample-limit} 条），供运维快速定位卡住的内容
 * @param engineId  引擎实例标识（多副本部署时区分来源；可空，部署未配则留空）
 * @param timestamp 告警触发时刻（Epoch millis）
 */
public record DlqAlert(int count, List<String> samples, String engineId, long timestamp) {
}
