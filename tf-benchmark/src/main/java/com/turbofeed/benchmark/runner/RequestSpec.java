package com.turbofeed.benchmark.runner;

/**
 * 一次待发出的请求（场景生成，压测器执行）。
 *
 * @param method {@code GET} 或 {@code POST}
 * @param path   以 {@code /} 开头的路径 + 查询串（相对 {@code --base-url}）
 * @param body   请求体，{@code null} 表示无体
 * @param auth   是否携带 {@code Authorization: Bearer}（未登录时探针会记失败，不静默降级）
 */
public record RequestSpec(String method, String path, String body, boolean auth) {

    public static RequestSpec get(String path, boolean auth) {
        return new RequestSpec("GET", path, null, auth);
    }

    public static RequestSpec post(String path, String body, boolean auth) {
        return new RequestSpec("POST", path, body, auth);
    }
}
