package com.turbofeed.benchmark.runner;

/**
 * 单线程延迟采集器：预分配 {@code long[]} 顺序追加，<b>无锁、无对象分配</b>。
 *
 * <p>为什么不用 {@code ArrayList<Long>}：每条样本一个 {@code Long} 装箱 + 一个数组槽，
 * 十万级请求就是十万次分配——压测端的 GC 会污染被测对象的延迟分布，测出来的 p99 里
 * 混着压测机自己的停顿。基本类型数组扩容摊还后几乎没有额外开销。</p>
 *
 * <p>为什么按线程分开存：多线程共享一个数组需要同步，而同步点会成为吞吐瓶颈；
 * 每线程本地写、结束后合并排序，代价只在收尾时付一次。</p>
 */
final class LatencyCollector {

    private static final int INITIAL_CAPACITY = 4096;

    private long[] samples = new long[INITIAL_CAPACITY];
    private int size;
    private long success;

    void record(long latencyNanos) {
        if (size == samples.length) {
            long[] grown = new long[samples.length << 1];
            System.arraycopy(samples, 0, grown, 0, samples.length);
            samples = grown;
        }
        samples[size++] = latencyNanos;
    }

    void success() {
        success++;
    }

    long successCount() {
        return success;
    }

    long[] toArray() {
        long[] out = new long[size];
        System.arraycopy(samples, 0, out, 0, size);
        return out;
    }
}
