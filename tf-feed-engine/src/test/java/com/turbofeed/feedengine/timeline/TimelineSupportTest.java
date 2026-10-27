package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.timeline.TimelineSupport.IndexEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static com.turbofeed.feedengine.timeline.TimelineSupport.bucketKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.dateOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.dateOfBucket;
import static com.turbofeed.feedengine.timeline.TimelineSupport.indexKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.indexValue;
import static com.turbofeed.feedengine.timeline.TimelineSupport.parseIndex;
import static com.turbofeed.feedengine.timeline.TimelineSupport.poolOfBucket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-1：桶键与反查索引「格式唯一定义」的契约测试。
 *
 * <p><b>为什么这些纯函数值得单独测</b>：它们原先在 6 处各写一遍（Store 的 promote/demote/
 * currentPool/inflowScoreOf/detach + pipeline 的 memberOf），且两组实现的边界判断已经分叉
 * （{@code sep <= 0} vs {@code sep < 0}）。这类分叉的症状不是抛异常，而是<b>静默失效</b>——
 * 下架删不掉、晋级找不到桶，所以必须用「畸形输入」把它钉住，而不是只测 happy path。</p>
 *
 * <p>纯函数测试：无 Spring、无 Redis、无 Docker。</p>
 */
class TimelineSupportTest {

    // ============ 桶键 ============

    @Test
    void bucketKey_hasExpectedFormat() {
        assertEquals("tf:feed:tl:2:20261027", bucketKey(2, "20261027"));
    }

    @Test
    void bucketKey_andParsers_roundTrip() {
        String bucket = bucketKey(3, "20261027");

        assertEquals(3, poolOfBucket(bucket));
        assertEquals("20261027", dateOfBucket(bucket));
    }

    @Test
    void dateOf_instantAndLocalDate_agree() {
        LocalDate day = LocalDate.now();

        assertEquals(dateOf(day), dateOf(Instant.now()));
        assertEquals(8, dateOf(day).length());     // BASIC_ISO_DATE = yyyyMMdd
    }

    // ============ 反查索引值 ============

    @Test
    void indexValue_andParseIndex_roundTrip() {
        String bucket = bucketKey(1, "20261027");
        String member = "{\"postId\":\"post/123/u1\"}";

        IndexEntry entry = parseIndex(indexValue(bucket, member));

        assertNotNull(entry);
        assertEquals(bucket, entry.bucketKey());
        // member 里可能含分隔符之外的任意字符，必须原样取回（JsonProcessing 不会转义 SOH）
        assertEquals(member, entry.member());
    }

    @Test
    void indexKey_prefixesTimelineKey() {
        assertEquals("tf:feed:idx:post/123/u1", indexKey("post/123/u1"));
    }

    @Test
    void parseIndex_nullOrNoSeparator_returnsNull() {
        assertNull(parseIndex(null));
        // 索引值写成了「只有桶 key」：定位得到桶但取不到成员串，无法 ZREM，必须判非法
        assertNull(parseIndex("tf:feed:tl:1:20261027"));
    }

    @Test
    void parseIndex_emptyBucketKey_returnsNull() {
        // 这一格正是原先两套实现的<b>分歧点</b>：Store 用 sep <= 0 判非法，pipeline 用 sep < 0
        // 放行了它并返回 member = "m1"。统一后必须双方一致地判非法 —— 空桶 key 无法定位桶。
        assertNull(parseIndex("\u0001m1"));
    }

    // ============ 桶 key 的字段提取（非法格式一律不猜） ============

    @Test
    void poolOfBucket_foreignPrefix_returnsMinusOne() {
        // 非 tl: 前缀一律 -1：从任意字符串硬抠数字会抠出一个「真实存在的桶」，让错误被当成成功
        assertEquals(-1, poolOfBucket("tf:feed:author:123"));
        assertEquals(-1, poolOfBucket("media/123/u1"));
        assertEquals(-1, poolOfBucket(null));
    }

    @Test
    void poolOfBucket_nonNumericPool_returnsMinusOne() {
        assertEquals(-1, poolOfBucket("tf:feed:tl:x:20261027"));
        assertEquals(-1, poolOfBucket("tf:feed:tl:"));
    }

    @Test
    void dateOfBucket_missingDate_returnsNull() {
        assertNull(dateOfBucket("tf:feed:tl:1"));
        assertNull(dateOfBucket("tf:feed:tl:1:"));
        assertNull(dateOfBucket("tf:feed:author:123"));
        assertNull(dateOfBucket(null));
    }
}
