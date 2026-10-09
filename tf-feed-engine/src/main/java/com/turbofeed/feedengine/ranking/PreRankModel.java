package com.turbofeed.feedengine.ranking;

/**
 * 粗排打分器（轻量、可插拔）。
 *
 * <p>精排前的廉价截断层：对召回候选快速打分、保留 Top-N 进精排，
 * 把「海量候选 × 贵精排」压成「少量候选 × 精排」，省算力、防长尾被一次性淹没。</p>
 */
public interface PreRankModel {

    /** 给一条候选打粗排分（越大越靠前）。 */
    double score(PreRankFeatures features);
}
