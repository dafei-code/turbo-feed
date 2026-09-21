package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存量内容补投（backfill）：把库里已是 {@code APPROVED}、却从未进入公域时间线的帖子补投进
 * tf-feed-engine 的读模型。
 *
 * <p><b>为什么需要它</b>：公域可见性是<b>写时物化</b>的——审核通过那一刻由
 * {@link FeedTimelinePublisher} 投递到引擎的 Redis ZSET。这条链路一旦在任何时刻断掉
 * （引擎没起、引擎连错 Redis、投递失败被 fail-open 吞掉），库里的 {@code APPROVED} 内容就
 * <b>永远不出现在发现流</b>，且没有任何自愈通道。2026-09-21 线上就是这个状态：库里 6 条
 * APPROVED，Redis 里 {@code tf:feed:*} 零 key，页面全白。
 * 本服务提供一次性的「按库重建时间线」能力，是这类事故的标准止血手段。</p>
 *
 * <p><b>幂等性</b>：可重复执行。引擎侧 {@code append} 会先按帖身份摘掉旧位置再写新位置，
 * 因此重复补投既不会产生重复条目，也不会让反查索引错乱。</p>
 *
 * <p><b>为什么用 {@code listApprovedGlobal}（跨分片广播）</b>：补投是<b>低频、运维触发</b>的
 * 操作，不是在线读路径。在线发现流绝不能广播（那是 {@code degraded-mode=local-scan} 都被
 * 标注为「仅本地/演示」的原因），但一次性补投没有更好的数据源——没有分片键可以点查
 * 「全平台 APPROVED」。代价是本次执行会扫全部分片，故调用方必须限制
 * {@code maxPosts} 并避开高峰。</p>
 *
 * <p><b>L0 不入池</b>：{@link CreditLevel#L0}（新人观察期 / 违规加严）语义是「先审后放，不进公域」，
 * 其 {@code poolLevel()=0}。补投时显式跳过这类账号的内容——否则会把「本不该进公域」的存量
 * 内容一次性放出去。（引擎侧 {@code appendStrict} 会把 0 兜底成 1，因此这道闸门必须由
 * 调用方把住，不能依赖引擎。）</p>
 */
@Service
public class FeedBackfillService {

    private static final Logger log = LoggerFactory.getLogger(FeedBackfillService.class);

    private final MediaJdbcRepository mediaRepository;
    private final AccountCreditService accountCreditService;
    private final FeedTimelinePublisher publisher;

    public FeedBackfillService(MediaJdbcRepository mediaRepository,
                               AccountCreditService accountCreditService,
                               FeedTimelinePublisher publisher) {
        this.mediaRepository = mediaRepository;
        this.accountCreditService = accountCreditService;
        this.publisher = publisher;
    }

    /**
     * 补投执行结果。
     *
     * @param scanned   扫描到的帖子数
     * @param delivered 成功投递的帖子数
     * @param skipped   被跳过的帖子数（L0 不入池 / mediaId 无法解析出 userId）
     * @param failed    投递或查询失败数
     * @param truncated 是否因达到 maxPosts 上限而提前结束（true 表示库里可能还有未补投的）
     */
    public record BackfillReport(int scanned, int delivered, int skipped, int failed, boolean truncated) {
    }

    /**
     * 扫描全平台 {@code APPROVED} 帖子并补投时间线。
     *
     * <p>分页采用 {@code OFFSET} 游标：补投期间内容仍在正常写入，严格一致性快照需要锁表，
     * 不值得。重复执行即最终一致，这比一次跑得"准"更重要。</p>
     *
     * @param batchSize 单批条数（≤0 兜底 50）
     * @param maxPosts  本次最多处理的帖子数（≤0 表示不限）
     */
    public BackfillReport backfillApproved(int batchSize, int maxPosts) {
        int batch = batchSize <= 0 ? 50 : batchSize;
        int max = maxPosts <= 0 ? Integer.MAX_VALUE : maxPosts;

        int scanned = 0;
        int delivered = 0;
        int skipped = 0;
        int failed = 0;
        boolean truncated = false;

        for (long offset = 0; scanned < max; offset += batch) {
            int limit = (int) Math.min(batch, (long) max - scanned);
            List<MediaItem> page;
            try {
                page = mediaRepository.listApprovedGlobal(limit, offset);
            } catch (Exception e) {
                log.error("补投扫描失败，中止本轮: offset={}, limit={}, {}", offset, limit, e.getMessage());
                failed++;
                break;
            }
            if (page.isEmpty()) {
                break;
            }
            for (MediaItem post : page) {
                scanned++;
                long userId;
                try {
                    userId = parseUserId(post.mediaId());
                } catch (IllegalArgumentException e) {
                    log.warn("补投跳过（mediaId 无法解析出 userId）: mediaId={}", post.mediaId());
                    skipped++;
                    continue;
                }
                int poolLevel;
                try {
                    poolLevel = accountCreditService.ensure(userId).poolLevel();
                } catch (Exception e) {
                    log.warn("补投取信用等级失败，按小池兜底: userId={}, {}", userId, e.getMessage());
                    poolLevel = 1;
                }
                if (poolLevel <= 0) {
                    // L0 = 新人观察期/违规加严：语义就是不进公域，补投也不能放出去
                    skipped++;
                    continue;
                }
                try {
                    // 必须看返回值：HTTP 实现是 fail-open，引擎全挂时 append 也不抛异常。
                    // 若一律按成功计，引擎宕机期间跑补投会得到「delivered=N, failed=0」——
                    // 一份把彻底失败包装成成功的报告，比没有报告更危险。
                    if (publisher.append(post, poolLevel)) {
                        delivered++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    // MQ 实现是 fail-fast：失败直接抛，不走返回值通道
                    log.warn("补投投递异常（不影响其余条目）: mediaId={}, {}", post.mediaId(), e.getMessage());
                    failed++;
                }
            }
            if (page.size() < limit) {
                break;                       // 已到末尾
            }
            if (scanned >= max) {
                truncated = true;            // 触达上限，库里可能还有
            }
        }

        log.info("时间线补投完成: 扫描={}, 已投递={}, 跳过={}, 失败={}, 触达上限={}",
                scanned, delivered, skipped, failed, truncated);
        return new BackfillReport(scanned, delivered, skipped, failed, truncated);
    }

    /**
     * 从 mediaId（{@code media/{userId}/{uuid}.{ext}}）解析归属 userId。
     *
     * <p>与 {@code MediaReviewService#parseUserId} 同逻辑，此处独立一份是因为它属于<b>补投</b>
     * 这条运维路径，不该为了复用去打开审核服务的可见性（审核服务的方法是 private 且带
     * {@code BizException} 语义，抛出的是用户侧参数错误，而补投要的是"跳过这条继续"）。</p>
     */
    private static long parseUserId(String mediaId) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId 为空");
        }
        String[] parts = mediaId.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("非法的 mediaId: " + mediaId);
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("mediaId 中的 userId 非法: " + mediaId);
        }
    }
}
