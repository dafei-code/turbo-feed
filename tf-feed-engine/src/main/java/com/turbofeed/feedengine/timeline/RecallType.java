package com.turbofeed.feedengine.timeline;

/**
 * 召回通道类型，用于编排时把各通道产出定位到终序的正确槽位：
 * <b>hot → follow → interest → cf → vector → pool → cold → ecosystem</b>。
 */
public enum RecallType {
    HOT,
    INTEREST,
    /** 记忆式 item-CF（抖音式「收藏又收藏」补充召回，复用双塔/A1 之外的共现信号）。 */
    CF,
    VECTOR,
    FOLLOW
}
