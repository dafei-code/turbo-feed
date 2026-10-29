package com.turbofeed.benchmark.scenario;

import com.turbofeed.benchmark.BenchOptions;
import com.turbofeed.benchmark.runner.RequestSpec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 场景注册表：<b>五场景</b>与真实网关端点一一对应，路径以 {@code /api/v1} 为准。
 *
 * <table border="1">
 *   <caption>场景与端点</caption>
 *   <tr><th>场景</th><th>请求</th><th>性质</th></tr>
 *   <tr><td>{@code feed}</td><td>{@code GET /api/v1/feed/recommended}</td><td>读（推荐主链路，最重）</td></tr>
 *   <tr><td>{@code playable}</td><td>{@code GET /api/v1/feed/playable}</td><td>读（播放地址批量取）</td></tr>
 *   <tr><td>{@code comment-list}</td><td>{@code GET /api/v1/comments}</td><td>读（keyset 深分页）</td></tr>
 *   <tr><td>{@code like}</td><td>{@code POST /api/v1/feed/media/like}</td><td><b>写</b>（计数 + 热 key）</td></tr>
 *   <tr><td>{@code comment-publish}</td><td>{@code POST /api/v1/comments}</td><td><b>写</b>（敏感词 + 分片落库）</td></tr>
 * </table>
 *
 * <p><b>为什么是这五个</b>：它们覆盖了「读主链路 / 读分页 / 写互动 / 写内容」四类压力形态，
 * 且都是线上真实入口。压测不该自造端点——自造端点测出来的是框架性能，不是业务性能。</p>
 */
public final class ScenarioRegistry {

    public static final String FEED = "feed";
    public static final String PLAYABLE = "playable";
    public static final String COMMENT_LIST = "comment-list";
    public static final String LIKE = "like";
    public static final String COMMENT_PUBLISH = "comment-publish";

    private static final List<String> ALL = List.of(FEED, PLAYABLE, COMMENT_LIST, LIKE, COMMENT_PUBLISH);

    private ScenarioRegistry() {
    }

    /** 全部场景名（用于用法提示与校验）。 */
    public static List<String> names() {
        return ALL;
    }

    /**
     * 创建场景。
     *
     * @throws IllegalArgumentException 场景名未知，或该场景需要 {@code --post-ids} 却没给
     */
    public static Scenario create(String name, BenchOptions options) {
        Scenario scenario = switch (name) {
            case FEED -> new FeedScenario(options.pageSize());
            case PLAYABLE -> new PlayableScenario();
            case COMMENT_LIST -> new CommentListScenario();
            case LIKE -> new LikeScenario();
            case COMMENT_PUBLISH -> new CommentPublishScenario();
            default -> throw new IllegalArgumentException(
                    "未知场景: " + name + "（可选: " + String.join(", ", ALL) + "）");
        };
        if (needsPostIds(scenario) && options.postIds().isEmpty()) {
            // 早失败：空池会退化成「每条请求都打空 mediaId」，测出来的数字没有意义。
            throw new IllegalArgumentException(
                    "场景 " + name + " 需要 --post-ids（逗号分隔的真实内容身份，如 --post-ids id1,id2）");
        }
        return scenario;
    }

    private static boolean needsPostIds(Scenario scenario) {
        return !FEED.equals(scenario.name());
    }

    /** 推荐流首页/翻页：分页深度取 0~2，避免永远只测第一页缓存。 */
    record FeedScenario(int pageSize) implements Scenario {
        @Override
        public String name() {
            return FEED;
        }

        @Override
        public boolean requiresAuth() {
            return true;
        }

        @Override
        public boolean writesData() {
            return false;
        }

        @Override
        public RequestSpec next(ScenarioContext ctx) {
            return RequestSpec.get(
                    "/api/v1/feed/recommended?page=" + ctx.randomInt(3) + "&size=" + pageSize, true);
        }
    }

    /** 批量取播放地址：一次 3 个内容身份，接近真机预加载行为。 */
    record PlayableScenario() implements Scenario {
        @Override
        public String name() {
            return PLAYABLE;
        }

        @Override
        public boolean requiresAuth() {
            return true;
        }

        @Override
        public boolean writesData() {
            return false;
        }

        @Override
        public RequestSpec next(ScenarioContext ctx) {
            return RequestSpec.get("/api/v1/feed/playable?postIds="
                    + String.join(",", ctx.randomPostIds(3)), true);
        }
    }

    /**
     * 评论列表：{@code newest} 与 {@code hot} 各半。
     *
     * <p>两种排序走的是两条不同读路径（游标扫描 vs 热头 ZSET），只测一种会漏掉一半的读压力。</p>
     */
    record CommentListScenario() implements Scenario {
        @Override
        public String name() {
            return COMMENT_LIST;
        }

        @Override
        public boolean requiresAuth() {
            return false;
        }

        @Override
        public boolean writesData() {
            return false;
        }

        @Override
        public RequestSpec next(ScenarioContext ctx) {
            String mode = ctx.randomInt(2) == 0 ? "newest" : "hot";
            return RequestSpec.get("/api/v1/comments?mediaId=" + encode(ctx.randomPostId())
                    + "&size=20&mode=" + mode, false);
        }
    }

    /** 点赞 toggle：真值与假值混发，覆盖「新增计数」与「撤回计数」两条写路径。 */
    record LikeScenario() implements Scenario {
        @Override
        public String name() {
            return LIKE;
        }

        @Override
        public boolean requiresAuth() {
            return true;
        }

        @Override
        public boolean writesData() {
            return true;
        }

        @Override
        public RequestSpec next(ScenarioContext ctx) {
            boolean like = ctx.randomInt(2) == 0;
            String body = "{\"postId\":\"" + ctx.randomPostId() + "\",\"like\":" + like + "}";
            return RequestSpec.post("/api/v1/feed/media/like", body, true);
        }
    }

    /**
     * 发评论：内容带全局递增序号，避免「重复内容被敏感词/去重逻辑挡掉」而把写压力测成读压力。
     *
     * <p><b>会产生真实数据</b>，只应在可清理的环境跑；报告头部会标注。</p>
     */
    record CommentPublishScenario() implements Scenario {
        @Override
        public String name() {
            return COMMENT_PUBLISH;
        }

        @Override
        public boolean requiresAuth() {
            return true;
        }

        @Override
        public boolean writesData() {
            return true;
        }

        @Override
        public RequestSpec next(ScenarioContext ctx) {
            String content = "bench-" + ctx.nextSeq();
            return RequestSpec.post("/api/v1/comments?mediaId=" + encode(ctx.randomPostId())
                    + "&content=" + encode(content), null, true);
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
