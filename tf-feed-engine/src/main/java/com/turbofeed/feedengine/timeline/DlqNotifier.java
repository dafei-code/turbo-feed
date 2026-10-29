package com.turbofeed.feedengine.timeline;

/**
 * 死信告警出口（P0-2 DLQ 告警部署接线）。
 *
 * <p>把"告警发到哪"与"何时发"解耦：{@link DlqAlertService} 只负责判定阈值/冷却并生成
 * {@link DlqAlert}，具体发出动作由本接口的实现决定——默认 {@link LogWebhookDlqNotifier}
 * 走"日志 ERROR + 可选 webhook POST"，测试可注入任意实现（如内存收集器）验证触发逻辑。</p>
 */
@FunctionalInterface
public interface DlqNotifier {

    /**
     * 发出一条死信告警。
     *
     * @param alert 告警载荷（count / samples / engineId / timestamp）
     */
    void notify(DlqAlert alert);
}
