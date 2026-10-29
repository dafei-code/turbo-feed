package com.turbofeed.benchmark.scenario;

import com.turbofeed.benchmark.runner.RequestSpec;

/**
 * 压测场景：把「业务动作」翻译成一条待发请求。
 *
 * <p><b>为什么用「生成请求」而不是「执行请求」</b>：场景只描述<b>打什么</b>，
 * 由 {@link com.turbofeed.benchmark.runner.LoadRunner} 负责并发/计时/统计。
 * 两者解耦后，新增一个场景只是加一个实现，不影响压测器；且场景本身无 IO，可单测。</p>
 *
 * <p><b>实现约束</b>：{@link #next} 会被多个线程同时调用，实现必须无共享可变状态
 * （随机数、序号一律从 {@link ScenarioContext} 取，它是每线程独立的）。</p>
 */
public interface Scenario {

    /** 场景名（命令行 {@code --scenario} 的取值）。 */
    String name();

    /** 是否需要登录令牌。 */
    boolean requiresAuth();

    /**
     * 是否产生真实写入。
     *
     * <p>压测报告会据此标注「本次压测改动了数据」——写场景跑在共享环境上要能一眼看见，
     * 否则事后分不清脏数据从哪来。</p>
     */
    boolean writesData();

    /** 生成下一条请求。 */
    RequestSpec next(ScenarioContext ctx);
}
