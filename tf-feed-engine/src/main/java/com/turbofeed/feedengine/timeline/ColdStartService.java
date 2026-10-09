package com.turbofeed.feedengine.timeline;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 冷启动探索服务（抖音式「探索利用 EE」G7）。
 *
 * <p><b>对标的是什么</b>：纯靠精排打分，新内容 / 新作者因为还没有互动样本，
 * 分值永远拼不过已验证优质老内容 → 「分数低就永远刷不到」的<b>饿死</b>。抖音用
 * <b>探索池</b>给新鲜内容固定曝光配额（EE：Exploration & Exploitation），用真实曝光换回样本，
 * 样本够了再交给赛马 / 精排正常竞争。本服务只决定「配额多少 + 何为新鲜」，
 * 真正取候选的 Redis 读取留在 {@link FeedTimelineStore}（复用其池读取与 parseMember）。</p>
 *
 * <p><b>fail-open</b>：关闭 / 配额 0 → 不注入；取候选异常由 {@link FeedTimelineStore} 捕获回退。</p>
 */
@Component
public class ColdStartService {

    private final ColdStartProperties props;

    public ColdStartService(ColdStartProperties props) {
        this.props = props;
    }

    /** 本页应注入的冷启动探索配额（条数）；关闭或比例≤0 返回 0。 */
    public int exploreBudget(int limit) {
        if (!props.isEnabled() || limit <= 0 || props.getRatio() <= 0d) {
            return 0;
        }
        return (int) Math.max(1, Math.floor(limit * props.getRatio()));
    }

    /** 新鲜内容的时间阈值：入流时刻晚于该时刻即视为待探索冷内容。 */
    public Instant coldThreshold(Instant now) {
        return now.minusSeconds(props.getWindowHours() * 3_600L);
    }

    /** 探索池只取前 maxPool 个池（新内容试水池）。 */
    public int maxPool() {
        return Math.max(1, props.getMaxPool());
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }
}
