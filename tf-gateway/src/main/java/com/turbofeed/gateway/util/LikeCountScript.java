package com.turbofeed.gateway.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 点赞计数 Key 的维护脚本（A1/A2 共用，media 点赞与评论点赞同一套语义）。
 *
 * <p><b>它解决什么</b>：{@code INCR}/{@code DECR} 对<b>不存在</b>的 Key 会创建为 0 再运算，
 * 于是计数从 0 起算而非「历史总数 + delta」。触发场景：计数 Key 首次引入（迁移期既有内容从未写过）、
 * 计数 Key TTL 过期而去重集合仍在、Redis 重建。不校正的话，一个已有 500 赞的帖子/评论
 * 被点赞后计数会<b>变成 1</b>；取消赞更会算出 {@code -1}。</p>
 *
 * <p><b>脚本语义</b>：{@code EXISTS} 命中 → {@code INCRBY + PEXPIRE} 并返回新值（热路径）；
 * 未命中 → 返回 {@code false}（RESP nil，Spring 映射为 {@code null}），由调用方走
 * 「64 槽去重集合重算」的冷启动校正分支。</p>
 *
 * <p><b>为什么用脚本而不是「先 EXISTS 再 INCR」两条命令</b>：两条命令之间不原子，
 * 并发下两个线程可能都判到「不存在」而双双走校正分支。脚本内 {@code EXISTS}+{@code INCRBY}
 * 由 Redis 单线程串行执行，判定与自增天然原子。</p>
 *
 * <p><b>为什么不用「返回值 == delta 即冷启动」这种免脚本写法</b>：会误报——新帖/新评论计数为 0 时
 * 被点赞，{@code INCR} 同样返回 1，于是每次都要白跑一次 64 槽聚合（{@code SCARD}×64），
 * 把 A1/A2 省下的读放大又还回去。</p>
 *
 * <p><b>Redis Cluster 安全性</b>：脚本只操作单个 {@code KEYS[1]}，不触发
 * {@code CROSSSLOT Keys in request don't hash to the same slot}（多 Key 脚本才要求同槽）。
 * 顺带把 {@code INCRBY} + {@code PEXPIRE} 合并为<b>一次往返</b>，比改造前的
 * {@code INCR} + {@code EXPIRE} 两次调用少一次 RTT。</p>
 *
 * @see com.turbofeed.gateway.service.like.MediaLikeService
 * @see com.turbofeed.gateway.service.comment.CommentLikeService
 */
public final class LikeCountScript {

    /**
     * 计数维护脚本。
     *
     * <p>入参约定：{@code KEYS[1]} = 计数 Key；{@code ARGV[1]} = 增量（可为负）；
     * {@code ARGV[2]} = TTL 毫秒数。</p>
     *
     * <p>返回：非 {@code null} = 热路径已完成自增与续期，值为新计数；
     * {@code null} = 计数 Key 原本不存在，需调用方做冷启动校正。</p>
     */
    public static final RedisScript<Long> COUNT_BUMP = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then "
                    + "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "return v "
                    + "else return false end",
            Long.class);

    private LikeCountScript() {
        // 常量持有类，不可实例化
    }
}
