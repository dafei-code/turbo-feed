package com.turbofeed.feedengine.ranking;

/**
 * 排序模型（精排）的输入特征。
 *
 * <p><b>为什么单独建模而不是把 ResponseEntity 直接传进来</b>：精排要接模型，就必须有一份
 * <b>稳定的特征契约</b>——今天由线性公式消费，明天换成远程推理服务时，这份 record 就是
 * 请求体的雏形（模型对特征是"列式"的，不对业务对象感兴趣）。把 {@code FeedItemView}
 * 直接喂给模型会把导出的长期演化绑死在视图结构上。</p>
 *
 * <p><b>为什么是原始计数而不是预先算好的比率</b>：比率（如"完播率=0.9"）一旦算出来就
 * <b>丢失了样本量信息</b>，而样本量恰恰是判断这个比率可不可信的关键（1 次曝光的 100%
 * 和 10000 次曝光的 100% 完全不是一回事）。保留 {@code impressions} 与成功数，
 * 置信度平滑（小样本向先验收敛）才有得做。这是本设计的核心取舍。</p>
 *
 * @param recencyMillis  入流时刻（epoch millis），决定"新不新"
 * @param impressions    曝光数（比率的样本量，平滑要用）
 * @param playCompletes  完播数
 * @param likes          点赞数
 * @param comments       评论数
 * @param shares         分享数
 * @param dislikes       负向数（踩 / 不感兴趣，此处只作记录，真正的用户级打压在 {@code negativeHit}）
 * @param interestMatch  该内容标签与用户兴趣画像的匹配分（已累加，未封顶）
 * @param negativeHit    是否命中该用户的负向标签（"不感兴趣"打压相似内容）
 */
public record RankingFeatures(double recencyMillis,
                              long impressions,
                              long playCompletes,
                              long likes,
                              long comments,
                              long shares,
                              long dislikes,
                              double interestMatch,
                              boolean negativeHit) {
}
