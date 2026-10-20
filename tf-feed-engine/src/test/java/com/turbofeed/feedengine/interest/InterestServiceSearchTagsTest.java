package com.turbofeed.feedengine.interest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * 搜索词入画像的回归防护。
 *
 * <p><b>为什么单独锁这一路</b>：它与行为事件入画像是<b>两个入口</b>，共享同一个 Lua 衰减脚本。
 * 最容易出的错全在"参数位置"上——脚本靠 {@code ARGV[1..6]} 的位置取 now / 半衰期 / minScore /
 * ttl / delta / 哨兵字段，少传一个或顺序错了脚本不会报错，只会<b>静默写错权重或写坏画像</b>
 * （例如把 ttl 当 delta，画像被乘上一个天文数字）。这类错误线上完全看不出来，只能靠断言拦住。</p>
 *
 * <p>同时锁住"只写长期层"与"两层都写"两种形态：短期层是可关的开关，
 * 关掉后不能还偷偷多打一次 Redis（那是白跑一遍 Lua）。</p>
 *
 * <p>⚠️ 计数一律走 {@link #evalCount}（数 mock 的调用记录），<b>不用
 * {@code verify(never()).execute(any(), any(), any())}</b>：{@code execute} 的第三个参数是
 * varargs，一旦用 matcher 去匹配 varargs，Mockito 的匹配语义随实际参数个数变化，
 * 匹配不上时 {@code never()} 会<b>空过</b>——测试永远是绿的，什么也没锁住。</p>
 */
@ExtendWith(MockitoExtension.class)
class InterestServiceSearchTagsTest {

    private static final String LONG_KEY = "tf:user:interest:u1";
    private static final String SHORT_KEY = "tf:user:interest:recent:u1";

    /** Lua 脚本里 ARGV 的下标（1-based 在脚本里，0-based 在 Java 数组里）。 */
    private static final int ARGV_NOW = 0;
    private static final int ARGV_HALF_LIFE = 1;
    private static final int ARGV_MIN_SCORE = 2;
    private static final int ARGV_TTL = 3;
    private static final int ARGV_DELTA = 4;
    private static final int ARGV_TS_FIELD = 5;
    private static final int ARGV_FIRST_TAG = 6;

    @Mock
    StringRedisTemplate redis;

    @Mock
    TagIndexService tagIndexService;

    private InterestService newService() {
        // 构造器注入即可；@Value 字段由下方按需用 ReflectionTestUtils 覆盖，
        // 不覆盖时走的是生产默认值（searchWeight=0.2 / 短期层开 / 半衰期 14d + 12h）
        return new InterestService(redis, tagIndexService);
    }

    /** 数一数这个 mock 上真正发生了多少次 {@code execute}（= 跑了多少次 Lua）。 */
    private static long evalCount(StringRedisTemplate template) {
        return mockingDetails(template).getInvocations().stream()
                .filter(i -> "execute".equals(i.getMethod().getName()))
                .count();
    }

    @Test
    @DisplayName("搜索词同时写长期层与短期层，权重取 search-weight")
    void writesBothLayersWithSearchWeight() {
        InterestService svc = newService();

        svc.accumulateSearchTags("u1", List.of("篮球", "nba"));

        ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argvCap = ArgumentCaptor.forClass(Object[].class);
        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keysCap.capture(), argvCap.capture());
        assertEquals(2, evalCount(redis), "长期层 + 短期层各一次 EVAL");

        List<List<String>> keys = keysCap.getAllValues();
        assertEquals(List.of(LONG_KEY), keys.get(0), "第一次 EVAL 必须写长期层");
        assertEquals(List.of(SHORT_KEY), keys.get(1), "第二次 EVAL 必须写短期层");

        List<Object[]> argvs = argvCap.getAllValues();
        Object[] longArgv = argvs.get(0);
        Object[] shortArgv = argvs.get(1);

        // 权重：默认 0.2，两层同权重（不同只是半衰期）
        assertEquals("0.2", String.valueOf(longArgv[ARGV_DELTA]), "长期层 delta 必须是 search-weight");
        assertEquals("0.2", String.valueOf(shortArgv[ARGV_DELTA]), "短期层 delta 必须同样是 search-weight");

        // 哨兵字段名必须传对，否则衰减基准时间无处可读 → 画像只增不减
        assertEquals("__ts__", String.valueOf(longArgv[ARGV_TS_FIELD]));
        assertEquals("__ts__", String.valueOf(shortArgv[ARGV_TS_FIELD]));

        // minScore 默认 0.01，TTL 长期 30d / 短期 7d
        assertEquals("0.01", String.valueOf(longArgv[ARGV_MIN_SCORE]));
        assertEquals(String.valueOf(30L * 86_400L), String.valueOf(longArgv[ARGV_TTL]), "长期层 TTL 应为 30 天");
        assertEquals(String.valueOf(7L * 86_400L), String.valueOf(shortArgv[ARGV_TTL]), "短期层 TTL 应为 7 天");

        // now 必须是毫秒时间戳（写成秒会让衰减因子恒为 1，画像退化成只增不减）
        long now = Long.parseLong(String.valueOf(longArgv[ARGV_NOW]));
        assertTrue(Math.abs(System.currentTimeMillis() - now) < 60_000L, "now 必须传 epoch 毫秒");

        // 两个标签都必须在 ARGV[7..] 之后（脚本从下标 7 起循环 HINCRBYFLOAT）
        assertEquals(ARGV_FIRST_TAG + 2, longArgv.length, "ARGV 长度应为 6 个固定参数 + 2 个标签");
        assertEquals("篮球", String.valueOf(longArgv[ARGV_FIRST_TAG]));
        assertEquals("nba", String.valueOf(longArgv[ARGV_FIRST_TAG + 1]));

        // 半衰期：长期 14d、短期 12h —— 两者不同正是"分层"的全部意义，写错就退化成单层
        double longHalfLife = Double.parseDouble(String.valueOf(longArgv[ARGV_HALF_LIFE]));
        double shortHalfLife = Double.parseDouble(String.valueOf(shortArgv[ARGV_HALF_LIFE]));
        assertEquals(14.0 * 86_400_000.0, longHalfLife, 1.0, "长期半衰期应为 14 天");
        assertEquals(12.0 * 3_600_000.0, shortHalfLife, 1.0, "短期半衰期应为 12 小时");
        assertTrue(shortHalfLife < longHalfLife, "短期半衰期必须显著短于长期，否则分层无意义");
    }

    @Test
    @DisplayName("短期层关闭时只写长期层，不多跑一次 Lua")
    void shortTermDisabledWritesLongTermOnly() {
        InterestService svc = newService();
        ReflectionTestUtils.setField(svc, "shortTermEnabled", false);

        svc.accumulateSearchTags("u1", List.of("篮球"));

        ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argvCap = ArgumentCaptor.forClass(Object[].class);
        verify(redis, org.mockito.Mockito.times(1))
                .execute(any(RedisScript.class), keysCap.capture(), argvCap.capture());
        assertEquals(List.of(LONG_KEY), keysCap.getValue());
        assertEquals(1, evalCount(redis), "短期层关闭后不应再打一次 Redis");
    }

    @Test
    @DisplayName("search-weight 配为 0 = 显式关闭搜索入画像，一次 Redis 都不打")
    void zeroWeightDisablesEntireSignal() {
        InterestService svc = newService();
        ReflectionTestUtils.setField(svc, "searchWeight", 0.0);

        svc.accumulateSearchTags("u1", List.of("篮球"));

        assertEquals(0, evalCount(redis), "search-weight=0 时不该产生任何写入");
    }

    @Test
    @DisplayName("匿名用户（userId 为 null/空白）不写画像")
    void blankUserIsIgnored() {
        InterestService svc = newService();

        svc.accumulateSearchTags(null, List.of("篮球"));
        svc.accumulateSearchTags("", List.of("篮球"));
        svc.accumulateSearchTags("   ", List.of("篮球"));

        assertEquals(0, evalCount(redis), "无 userId 就无画像，一次都不该写");
    }

    @Test
    @DisplayName("空标签列表不写画像（搜索无命中话题时不该产生任何权重）")
    void emptyTagsAreIgnored() {
        InterestService svc = newService();

        svc.accumulateSearchTags("u1", null);
        svc.accumulateSearchTags("u1", List.of());

        assertEquals(0, evalCount(redis), "空标签不该产生任何写入");
    }

    @Test
    @DisplayName("Redis 异常不得冒泡：搜索结果绝不能因为画像写失败而失败")
    void redisFailureIsFailOpen() {
        // 用「默认答案就抛异常」的 mock，绕开 varargs matcher 的匹配陷阱：
        // 这样任何对 redis 的调用都会失败，无需精确匹配参数
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class, inv -> {
            throw new RuntimeException("redis down");
        });
        InterestService svc = new InterestService(brokenRedis, tagIndexService);

        assertDoesNotThrow(() -> svc.accumulateSearchTags("u1", List.of("篮球")));

        // 长期层（第一次 EVAL）失败后整个 try 块中断，短期层不再尝试——
        // 两层打的是同一个 Redis，一次连不上时再打一次只是多耗一次超时。
        // 断言"只尝试了一次"，把该行为固定下来
        assertEquals(1, evalCount(brokenRedis), "失败后不应继续尝试第二次");
    }
}
