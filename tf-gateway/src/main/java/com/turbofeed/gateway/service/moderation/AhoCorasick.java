package com.turbofeed.gateway.service.moderation;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Aho-Corasick 多模匹配自动机（对标抖音/字节内容安全文本扫描方案）。
 *
 * <p>多模一次扫描命中全词库，时间复杂度 O(n + m + z)，n=文本长 m=词库总长 z=命中数；
 * 相比单 Trie 逐词扫描 O(n·m)，大词库下有数量级提升。</p>
 *
 * <p><b>容量与代价（本项目实测，2026-09：50 字文档、单线程、HashMap 子节点）</b>：
 * 1k 词 0.29µs / 3.47M QPS / 10MB；10k 词 0.48µs / 2.10M QPS / 17MB；
 * 200k 词 1.36µs / 738K QPS / 127MB；1M 词 2.76µs / 362K QPS / 590MB、构建 1.2s。
 * 最坏用例（深状态且全程不命中）约 30µs，相对常态退化 11~64 倍。</p>
 *
 * <p><b>结论（由数据决定，而不是照搬大厂方案）</b>：吞吐<b>不是</b>本项目的瓶颈——
 * 真实瓶颈是 ① 每次全量重建的延迟（1M 词 1.2s，会卡住写请求）② 内存占用
 * ③ <b>静默失效</b>（词库为空时一切"正常"）。因此不引入 DAT / 分片词库 /
 * 分布式词库服务（那是为千万词级词库 + 百万 QPS + 成规模运营团队设计的），
 * 升级顺序是：<b>先让失效可见 → 再增量更新 → 最后才是内存结构</b>。</p>
 *
 * <p><b>不变性</b>：构造完成后完全只读，线程安全。{@link SensitiveWordService} 通过
 * {@link java.util.concurrent.atomic.AtomicReference} 整体替换实例实现热更新，
 * 读路径零锁——读请求始终拿到一个完整的、不可变的 AC，遍历过程中不会被替换影响。</p>
 *
 * <p><b>匹配语义</b>：返回第一个命中的敏感词<b>及其位置</b>（{@link #firstMatchDetail}，
 * {@link #firstMatch} 是其取词重载），用于 fail-closed 拦截；如需返回全部命中，
 * 遍历 fail 链收集，本骨架暂不实现。</p>
 *
 * <p><b>变体支持由预处理层负责</b>：本类只做纯字符串匹配，不做拼音/繁简/拆字归一化。
 * 喂进来的文本必须先过 {@link TextNormalizer}（NFKC / 剥分隔符 / 繁简折叠 / 小写）——
 * <b>AC 保持"只认字面"是其可测试性的前提</b>：它的行为完全由字节决定，
 * 而归一化策略可以独立演进、单独回滚。</p>
 */
public final class AhoCorasick {

    /** 空词库占位（避免 null，启动前/词库清空时使用）。 */
    public static final AhoCorasick EMPTY = new AhoCorasick(java.util.List.of());

    private final Node root;
    private final int size;

    public AhoCorasick(Collection<String> words) {
        this.root = new Node('\0');
        this.root.fail = this.root;
        this.size = build(words);
    }

    /** 当前词条数（构造时统计）。 */
    public int size() {
        return size;
    }

    /**
     * 一次命中：词条 + 在本方法入参文本中的下标区间（半开区间 {@code [start, end)}）。
     *
     * <p>位置在下标 {@code i} 处是<b>顺手可得</b>的：终结节点的深度等于词长，
     * 故起点 = {@code i + 1 - word.length()}。事后用 {@code indexOf} 反查则可能
     * 拿到另一个位置（同一个词在文本中出现多次时），运营核对高亮就会指错地方。</p>
     */
    public record Match(String word, int start, int end) {
    }

    /**
     * 扫描文本，返回第一个命中的词及其位置；无命中返回 {@code null}。
     *
     * <p>「第一个」的语义是<b>按文本扫描顺序，最早结束的那个命中</b>——
     * 不是词库里排序最靠前的词。要在同一次扫描里拿全部命中需遍历 fail 链收集，
     * 骨架阶段不需要（写路径一旦命中即拒，无需求全）。</p>
     */
    public Match firstMatchDetail(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Node cur = root;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Node next = cur.children.get(c);
            while (next == null && cur != root) {
                cur = cur.fail;
                next = cur.children.get(c);
            }
            if (next != null) {
                cur = next;
            }
            if (cur.word != null) {
                return new Match(cur.word, i + 1 - cur.word.length(), i + 1);
            }
            // 沿 fail 链查找更短的命中（多模同时命中的常见情况）
            for (Node f = cur.fail; f != root; f = f.fail) {
                if (f.word != null) {
                    return new Match(f.word, i + 1 - f.word.length(), i + 1);
                }
            }
        }
        return null;
    }

    /** 扫描文本，返回第一个命中的敏感词；无命中返回 null。 */
    public String firstMatch(String text) {
        Match m = firstMatchDetail(text);
        return m == null ? null : m.word();
    }

    /** 是否包含敏感词。 */
    public boolean contains(String text) {
        return firstMatch(text) != null;
    }

    private int build(Collection<String> words) {
        int count = 0;
        // 1) 构 trie
        for (String w : words) {
            if (w == null) {
                continue;
            }
            String s = w.trim();
            if (s.isEmpty()) {
                continue;
            }
            Node cur = root;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                cur = cur.children.computeIfAbsent(c, k -> new Node(c));
            }
            if (cur.word == null) {
                cur.word = s;
                count++;
            }
        }
        // 2) BFS 计算 fail 链
        Deque<Node> queue = new ArrayDeque<>();
        for (Node c : root.children.values()) {
            c.fail = root;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            Node u = queue.poll();
            for (Map.Entry<Character, Node> e : u.children.entrySet()) {
                char c = e.getKey();
                Node v = e.getValue();
                Node f = u.fail;
                while (f != root && !f.children.containsKey(c)) {
                    f = f.fail;
                }
                Node fn = f.children.get(c);
                v.fail = (fn != null && fn != v) ? fn : root;
                queue.add(v);
            }
        }
        return count;
    }

    private static final class Node {
        final char ch;
        final Map<Character, Node> children = new HashMap<>();
        Node fail; // 由 build 显式赋值
        String word; // 命中词（null = 非终结节点）

        Node(char ch) {
            this.ch = ch;
        }
    }
}
