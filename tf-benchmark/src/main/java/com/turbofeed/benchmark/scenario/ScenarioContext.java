package com.turbofeed.benchmark.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景上下文：<b>每线程一份</b>，承载随机源与唯一序号。
 *
 * <p>为什么随机源不做全局共享：{@link java.util.Random} 是线程安全的但内部 CAS 会在线程多时
 * 成为竞争点；{@code ThreadLocalRandom} 无锁，压测端不该把 CPU 花在自己的随机数上。
 * 序号（评论内容唯一性）用全局 {@link AtomicLong}，因为它必须真唯一，且每次请求只取一次。</p>
 */
public final class ScenarioContext {

    private static final AtomicLong SEQ = new AtomicLong();

    private final List<String> postIds;
    private final int pageSize;

    public ScenarioContext(List<String> postIds, int pageSize) {
        this.postIds = List.copyOf(postIds);
        this.pageSize = pageSize;
    }

    public int pageSize() {
        return pageSize;
    }

    public boolean hasPostIds() {
        return !postIds.isEmpty();
    }

    /** 随机取一个内容身份；池为空时抛错（调用方应在启动前校验）。 */
    public String randomPostId() {
        if (postIds.isEmpty()) {
            throw new IllegalStateException("内容身份池为空：写场景必须提供 --post-ids");
        }
        return postIds.get(ThreadLocalRandom.current().nextInt(postIds.size()));
    }

    /** 随机取最多 {@code n} 个内容身份（去重、不重复抽）。 */
    public List<String> randomPostIds(int n) {
        if (postIds.isEmpty()) {
            throw new IllegalStateException("内容身份池为空：playable 场景必须提供 --post-ids");
        }
        int take = Math.min(n, postIds.size());
        if (take == postIds.size()) {
            return postIds;
        }
        // 池子小（一般 <10），随机起点 + 顺序切片即可，不值得为它引入洗牌与临时集合。
        // 取模环绕：起点靠后时不会越界，也不会因为「凑不够就少给」而让请求形态抖动。
        int start = ThreadLocalRandom.current().nextInt(postIds.size());
        List<String> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            out.add(postIds.get((start + i) % postIds.size()));
        }
        return List.copyOf(out);
    }

    /** 0 ~ bound-1 的随机整数。 */
    public int randomInt(int bound) {
        return ThreadLocalRandom.current().nextInt(Math.max(1, bound));
    }

    /** 全局递增序号（用于生成不重复的评论内容）。 */
    public long nextSeq() {
        return SEQ.incrementAndGet();
    }
}
