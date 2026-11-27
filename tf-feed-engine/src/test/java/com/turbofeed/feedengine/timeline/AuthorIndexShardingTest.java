package com.turbofeed.feedengine.timeline;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 作者索引分片 key 格式单测：验证 shards=1 零回归、shards>1 按 viewer 稳定路由、
 * 且所有 viewer 的读分片都落在写入的 N 份副本内（否则会出现「某用户永远看不到该作者内容」）。
 */
class AuthorIndexShardingTest {

    @Test
    void singleShard_keepsLegacyKey() {
        assertEquals("tf:feed:author:123", authorIndexKey("123", 0, 1));
        assertEquals("tf:feed:author:123", authorIndexKey("123", 5, 1)); // 任意 shard 参数都退化为单 key
        assertEquals(0, authorIndexShardFor("viewer-1", 1));
        assertEquals(0, authorIndexShardFor(null, 1));
    }

    @Test
    void sharded_keysIncludeShardAndViewerRoutingIsStable() {
        assertEquals("tf:feed:author:123:shard:0", authorIndexKey("123", 0, 8));
        assertEquals("tf:feed:author:123:shard:7", authorIndexKey("123", 7, 8));

        for (int shards : new int[]{2, 4, 8, 16}) {
            int first = authorIndexShardFor("viewer-1", shards);
            assertEquals(first, authorIndexShardFor("viewer-1", shards), "同一 viewer 必须稳定落同一分片");
            assertTrue(first >= 0 && first < shards);
            assertEquals(0, authorIndexShardFor(null, shards));
        }
    }

    @Test
    void everyViewerShardIsAmongWrittenReplicas() {
        int shards = 8;
        // 模拟写入：写 shards 份副本
        Set<String> written = new HashSet<>();
        for (int s = 0; s < shards; s++) {
            written.add(authorIndexKey("bigV", s, shards));
        }
        // 模拟读取：任意 viewer 选中的分片必须已在写入副本里
        for (int i = 0; i < 1000; i++) {
            int shard = authorIndexShardFor("viewer-" + i, shards);
            assertTrue(written.contains(authorIndexKey("bigV", shard, shards)),
                    "viewer-" + i + " 读的分片未被写入");
        }
    }
}
