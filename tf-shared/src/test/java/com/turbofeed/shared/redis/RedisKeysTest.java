package com.turbofeed.shared.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RedisKeys} 单测（tf-shared 契约库，M1 / 3.5）。
 *
 * <p><b>为什么锁字面量</b>：这些前缀是<b>跨服务契约</b>——一侧写、另一侧读。
 * 如果有人改了字面量却没有同步另一侧的读取方，不会报任何错，只表现为「读不到」。
 * 因此这里把字面量钉死，任何改动必须先过这道测试，逼着改动者去想兼容性。</p>
 */
class RedisKeysTest {

    @Test
    void followConstants_matchContract() {
        assertEquals("tf:follow:", RedisKeys.FOLLOW);
        assertEquals("tf:follower:", RedisKeys.FOLLOWER);
    }

    @Test
    void follow_buildsKeys() {
        assertEquals("tf:follow:1001", RedisKeys.follow(1001L));
        assertEquals("tf:follower:1001", RedisKeys.follower(1001L));
    }

    @Test
    void postStatKey_matchesEngineWriterAndGatewayReader() {
        assertEquals("tf:post:stat:", RedisKeys.POST_STAT);
        assertEquals("tf:post:stat:post/1/abc", RedisKeys.postStat("post/1/abc"));
    }

    @Test
    void tagMediaKeys_matchEngineWriterAndGatewayReader() {
        assertEquals("tf:tag:media:", RedisKeys.TAG_MEDIA);
        assertEquals("tf:tag:media:z:", RedisKeys.TAG_MEDIA_ORDERED);
        assertEquals("tf:tag:media:java", RedisKeys.tagMedia("java"));
        assertEquals("tf:tag:media:z:java", RedisKeys.tagMediaOrdered("java"));
    }

    @Test
    void moderationKey_usesNamespace() {
        assertEquals("tf:mod:", RedisKeys.MODERATION_DEFAULT_NAMESPACE);
        assertEquals("tf:mod:post/1/abc", RedisKeys.moderation("tf:mod:", "post/1/abc"));
        assertEquals("tf:custom:post/1/abc", RedisKeys.moderation("tf:custom:", "post/1/abc"));
    }

    @Test
    void moderationKey_blankNamespaceFallsBackToDefault() {
        // 空值回退口径必须唯一：否则写侧用默认、读侧用空串 = 两边 key 对不上
        assertEquals("tf:mod:p1", RedisKeys.moderation(null, "p1"));
        assertEquals("tf:mod:p1", RedisKeys.moderation("", "p1"));
        assertEquals("tf:mod:p1", RedisKeys.moderation("   ", "p1"));
    }
}
