package com.turbofeed.gateway.service.event.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发件箱分发注册表（PR-C3）：把「枚举值 → 处理器」的完整性校验从运行时提前到<b>启动期</b>。
 *
 * <h3>为什么不用 switch 表达式</h3>
 * <p>switch 表达式确实能拿回编译期穷尽检查，但它把所有事件的投递逻辑堆在
 * {@code OutboxService} 一个类里。注册表让「新增事件 = 新增一个 Handler 类」，且校验发生在
 * <b>Bean 装配完成时</b>——比「编译期」多覆盖了「Bean 被条件注解摘掉」这类运行时装配差异
 * （例如某 Handler 误加了 {@code @ConditionalOnProperty}，生产 profile 下不注册）。
 * 编译期检查发现不了这种缺失，本类的启动期 fail-fast 能。</p>
 *
 * <h3>验收</h3>
 * <p>注释掉任一 Handler 的 {@code @Component} → 应用启动失败，报错信息点名缺失的枚举值。</p>
 *
 * <h3>为什么构造期就校验，不等 {@code afterPropertiesSet}</h3>
 * <p>本类的唯一输入是 Spring 收齐的 {@code List<OutboxEventHandler>}，构造完成的那一刻
 * 信息已经完备；构造期抛异常与 {@code InitializingBean} 效果等价，但单测可以直接
 * {@code new OutboxHandlerRegistry(List.of(...))} 覆盖，不需要起 Spring 上下文。</p>
 */
@Component
public class OutboxHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(OutboxHandlerRegistry.class);

    private final Map<OutboxEventType, OutboxEventHandler> handlers = new EnumMap<>(OutboxEventType.class);

    public OutboxHandlerRegistry(List<OutboxEventHandler> beans) {
        if (beans != null) {
            for (OutboxEventHandler handler : beans) {
                if (handler == null || handler.type() == null) {
                    throw new IllegalStateException("Outbox 处理器声明了空类型: " + handler);
                }
                OutboxEventHandler previous = handlers.putIfAbsent(handler.type(), handler);
                if (previous != null) {
                    // 两个 Bean 抢一个类型：静默取其中一个会让另一条链路永远不投，必须拒绝启动。
                    throw new IllegalStateException("Outbox 事件类型重复注册: " + handler.type()
                            + " (" + previous.getClass().getName() + " vs "
                            + handler.getClass().getName() + ")");
                }
            }
        }
        for (OutboxEventType type : OutboxEventType.values()) {
            if (!handlers.containsKey(type)) {
                throw new IllegalStateException("Outbox 事件类型缺少分发处理器: " + type);
            }
        }
        log.info("Outbox 分发注册表就绪: {} 个事件类型全部有处理器", handlers.size());
    }

    /**
     * 取某类型的处理器；未登记时抛异常（而不是返回 null 静默跳过）。
     * 正常路径下启动期已校验过，这里只剩「库里写了一个代码不认识的字符串」这一种可能。
     */
    public OutboxEventHandler require(OutboxEventType type) {
        OutboxEventHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("Outbox 事件类型缺少分发处理器: " + type);
        }
        return handler;
    }

    /** 已登记类型集合（只读快照，供单测与运维自检）。 */
    public Set<OutboxEventType> registeredTypes() {
        return Set.copyOf(handlers.keySet());
    }
}
