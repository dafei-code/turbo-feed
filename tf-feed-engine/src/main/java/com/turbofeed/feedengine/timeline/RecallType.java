package com.turbofeed.feedengine.timeline;

/**
 * 召回通道类型，用于编排时把各通道产出定位到终序的正确槽位：
 * <b>hot → follow → interest → vector → pool → cold → ecosystem</b>。
 */
public enum RecallType {
    HOT,
    INTEREST,
    VECTOR,
    FOLLOW
}
