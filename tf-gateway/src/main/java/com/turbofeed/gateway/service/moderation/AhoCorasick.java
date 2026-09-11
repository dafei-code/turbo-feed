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
 * 相比单 Trie 逐词扫描 O(n·m)，大词库下有数量级提升。基于双数组 Trie (DAT) 的实现
 * 内存更紧，但骨架阶段 HashMap 子节点已足够（词库 < 10w 时内存与速度均可接受）。</p>
 *
 * <p><b>不变性</b>：构造完成后完全只读，线程安全。{@link SensitiveWordService} 通过
 * {@link java.util.concurrent.atomic.AtomicReference} 整体替换实例实现热更新，
 * 读路径零锁——读请求始终拿到一个完整的、不可变的 AC，遍历过程中不会被替换影响。</p>
 *
 * <p><b>匹配语义</b>：返回第一个命中的敏感词（{@link #firstMatch}），用于 fail-closed
 * 拦截；如需返回全部命中，遍历 fail 链收集，本骨架暂不实现。</p>
 *
 * <p><b>变体支持</b>：当前为纯字符串匹配，未做拼音/繁简/拆字归一化——生产对抗绕过需
 * 预处理层把变体归一后再喂入 AC（骨架阶段预留扩展点）。</p>
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

    /** 扫描文本，返回第一个命中的敏感词；无命中返回 null。 */
    public String firstMatch(String text) {
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
                return cur.word;
            }
            // 沿 fail 链查找更短的命中（多模同时命中的常见情况）
            for (Node f = cur.fail; f != root; f = f.fail) {
                if (f.word != null) {
                    return f.word;
                }
            }
        }
        return null;
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
