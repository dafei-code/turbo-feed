package com.turbofeed.gateway.service.event;

/**
 * 媒体事件发布端口（Observer 模式：事件总线抽象，业务层只依赖本接口）。
 *
 * <p>实现二选一（由配置 {@code turbofeed.mq.enabled} 切换，保证"克隆即跑"）：</p>
 * <ul>
 *   <li>{@code LocalMediaEventPublisher} —— 默认，走 Spring 应用内事件（零外部依赖）</li>
 *   <li>{@code RocketMqMediaEventPublisher} —— 配置 {@code turbofeed.mq.enabled=true}
 *       + {@code rocketmq.name-server} 后激活，事件跨进程投递（削峰 / 解耦 / 失败重试）</li>
 * </ul>
 *
 * <p>发布方（上传服务）不感知具体实现——这正是端口 / 适配器思想：事件总线可替换，
 * 业务代码零改动。</p>
 */
public interface MediaEventPublisher {

    /**
     * 发布媒体上传事件。
     *
     * @param event 上传完成事件
     */
    void publish(MediaUploadedEvent event);
}
