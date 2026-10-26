package com.turbofeed.shared.model;

/**
 * 契约版本不被当前运行的代码支持（M2 / 4.1）。
 *
 * <p><b>为什么继承 {@link IllegalArgumentException}（不是图省事）</b>：消费端<b>已有</b>的
 * 「拒绝处理但不改动任何数据」分支正是 {@code catch (IllegalArgumentException)}
 * ——引擎的时间线投递把它翻译成「HTTP 兜底通道只告警、MQ 顺序通道上抛交给 RocketMQ
 * 重试 / DLQ」。版本不兼容属于同一类情形：<b>这条消息无法被安全地解释</b>。复用同一条
 * 处理路径，才能让它在两个通道上自动得到正确处置，而不是新增一条只有一半通道认识的
 * 分支——后者正是「双通道副作用各写一遍」缺陷的翻版。</p>
 *
 * <p><b>与「未知 action」同源</b>：两个异常都表达「消息我不认」，处置也一致——
 * 绝不按某个默认值（REMOVE / 当版本）兜底继续处理。误把一条坏消息当正常消息处理，
 * 比丢掉它危险得多。</p>
 */
public class UnsupportedContractVersionException extends IllegalArgumentException {

    private final String contract;
    private final int seen;
    private final int supported;

    public UnsupportedContractVersionException(String contract, int seen, int supported) {
        super("契约版本不兼容，拒绝按当前语义处理（不改动任何数据）: contract=" + contract
                + ", seen=" + seen + ", supported=" + supported);
        this.contract = contract;
        this.seen = seen;
        this.supported = supported;
    }

    /** 契约名（用于日志与 DLQ 巡检定位）。 */
    public String contract() {
        return contract;
    }

    /** 消息里携带的版本。 */
    public int seen() {
        return seen;
    }

    /** 当前代码能解释的最高版本。 */
    public int supported() {
        return supported;
    }
}
