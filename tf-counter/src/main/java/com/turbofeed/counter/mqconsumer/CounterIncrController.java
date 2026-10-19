package com.turbofeed.counter.mqconsumer;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.service.CounterIncrService;
import com.turbofeed.shared.model.CounterIncrementEvent;
import com.turbofeed.shared.model.CounterView;
import com.turbofeed.shared.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * tf-counter 内部计数接口（仅内网可达）。
 *
 * <ul>
 *   <li>{@code POST /internal/counter/incr}：批量接收计数增量（网关 HTTP 兜底默认走此；
 *       MQ 启用时由 {@link CounterMqConsumer} 消费同契约事件后汇流到 {@link CounterIncrService}）。</li>
 *   <li>{@code GET /internal/counter/{postId}}：三级读缓存查询聚合计数（管理端 / 批量计数用）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/counter")
public class CounterIncrController {

    private final CounterIncrService incrService;
    private final CounterReadService readService;

    public CounterIncrController(CounterIncrService incrService, CounterReadService readService) {
        this.incrService = incrService;
        this.readService = readService;
    }

    @PostMapping("/incr")
    public Result<Void> incr(@RequestBody List<CounterIncrementEvent> events) {
        if (events != null) {
            for (CounterIncrementEvent e : events) {
                incrService.accept(e);
            }
        }
        return Result.ok();
    }

    @GetMapping("/{postId}")
    public Result<CounterView> get(@PathVariable String postId) {
        return Result.ok(readService.get(postId));
    }
}
