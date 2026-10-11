package com.turbofeed.feedengine.recall;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 用户向量来源生效条件：{@code mode=model} 或 {@code mode=ann} 时激活。
 *
 * <p>@ConditionalOnProperty 的 havingValue 只支持单值，无法表达「model 或 ann」；故抽成
 * 显式 Condition。ann 模式复用 KV 用户向量（A1 训练 / 内联聚合），与 model 同源，仅召回路径不同。</p>
 */
public class VectorUserSourceEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String v = ctx.getEnvironment().getProperty("turbofeed.feed.recall.vector.mode");
        return "model".equalsIgnoreCase(v) || "ann".equalsIgnoreCase(v);
    }
}
