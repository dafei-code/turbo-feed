package com.turbofeed.gateway.service.behavior;

import java.time.Instant;

/**
 * 异常行为日志值对象（P2-2，抖音式审核闭环的数据底座）。
 *
 * <p>把审核相关行为（举报 / 评论异常 / 处置）持久化，供 P2-1 的审核信号做<b>闭环来源</b>：
 * 离线跑恶意账号聚类、举报人信用再训练、全局举报水位因子校准。本对象是纯数据载体，
 * 不依赖 Lombok（新增文件需显式构造器/访问器）。</p>
 */
public class BehaviorLog {

    private final long userId;
    private final String mediaId;
    private final String action;
    private final String detail;
    private final Instant createdAt;

    public BehaviorLog(long userId, String mediaId, String action, String detail, Instant createdAt) {
        this.userId = userId;
        this.mediaId = mediaId;
        this.action = action;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public long userId() {
        return userId;
    }

    public String mediaId() {
        return mediaId;
    }

    public String action() {
        return action;
    }

    public String detail() {
        return detail;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
