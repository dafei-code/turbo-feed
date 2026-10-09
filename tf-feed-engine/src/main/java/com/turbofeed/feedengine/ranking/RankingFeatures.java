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
 * @param interestMatch  该内容标签与用户<b>长期</b>兴趣画像的匹配分（已累加，未封顶）
 * @param shortTermMatch 该内容标签与用户<b>短期</b>兴趣画像的匹配分（最近半天的突发兴趣；
 *                       短期层关闭时恒为 0）。与 {@code interestMatch} 同源标签、各自独立累加，
 *                       二者叠加即"长期偏好 + 当下追更"的双重信号——这是抖音式"你刚刷了一堆钓鱼，
 *                       现在就多给你钓鱼"的精排落地处（召回只解决"看不看得见"，精排才决定"排多前"）。
 * @param sessionMatch   该内容标签与用户<b>最近互动序列</b>的匹配分（session 级 target-attention 最简形态：
 *                       候选标签与你"刚看过/点过"的内容重合度 × 那些互动的时效权重之和）。
 *                       session 关闭 / 冷 session 时为 0。它捕捉长期+短期画像都丢掉的<b>局部强时效</b>信号——
 *                       "刚连着看了 5 条钓鱼"此刻就该多给钓鱼，即使长期画像里钓鱼权重不高。
 * @param negativeHit    是否命中该用户的负向标签（"不感兴趣"打压相似内容）
 * @param realtimeMatch  实时特征匹配分（抖音式「实时特征流」接入点）：由 {@code RealtimeFeatureService}
 *                        聚合 session 级最近互动 + 短期兴趣 burst 成的页面级实时画像，与本内容标签的亲和度之和。
 *                        缺失 / 冷启动 / 读失败 = 0.0（fail-open 不增强也不打压）。
 * @param authorHealthScale 作者健康分排序系数（抖音式「审核与推荐解耦」接入点）：DEMOTE 档(健康分[60,80))
 *                       由 {@code ReviewSignalClient} 赋 {@code demoteScale}(默认 0.5，与 P0-b「推荐降权 0.5」对齐)，
 *                       其余档 / 缺失 / 读失败均 = 1.0（fail-open 不降权）。BANNED 与 &lt;60 作者已被召回层剔除，
 *                       不会流入此处。模型末尾以 {@code result * authorHealthScale} 施加——系数越小越靠后。
 */
public record RankingFeatures(double recencyMillis,
                              long impressions,
                              long playCompletes,
                              long likes,
                              long comments,
                              long shares,
                              long dislikes,
                              double interestMatch,
                              double shortTermMatch,
                              double sessionMatch,
                              boolean negativeHit,
                              double authorHealthScale,
                              double realtimeMatch) {
}
