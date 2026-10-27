package com.turbofeed.gateway.web;

/**
 * 对外 HTTP 接口的版本前缀（improvement-plan §P2-3）。
 *
 * <p><b>为什么把版本写进路径</b>：客户端一旦发出去就收不回来——移动端、第三方脚本、缓存的旧页面
 * 都可能长期以老形态调用。路径版本是唯一「服务端单方面可控」的演进手段：新版本挂新前缀，
 * 老前缀继续服务并向老客户端下发弃用信号，而不是靠请求头协商（很多客户端改不了请求头）。</p>
 *
 * <p><b>为什么常量集中在这里</b>：17 个控制器的类级 {@code @RequestMapping} 都要以它开头，
 * 且它能出现在注解里必须保持 {@code public static final String} 编译期常量。
 * 集中后「升级到 v2」是改一处常量 + 新增一组 v2 控制器，而不是在 17 个文件里搜索替换。
 * {@code ArchitectureTest#对外接口必须挂在版本前缀下} 会拦住漏改的控制器。</p>
 */
public final class ApiVersion {

    /**
     * 当前对外版本前缀。所有 {@code controller} 包的类级 {@code @RequestMapping} 必须以它开头。
     *
     * <p>升级 v2 的正确姿势：<b>不要</b>把本常量改成 {@code /api/v2}（那会让所有老客户端当天失效），
     * 而是新增 {@code V2} 常量与一组 v2 控制器，本常量继续服务 v1。</p>
     */
    public static final String V1 = "/api/v1";

    /**
     * 版本化之前的老前缀（{@code /api/**}）。仍然可用，但会被
     * {@link LegacyApiPathFilter} 内部重写到 {@link #V1}，并在响应上带
     * {@code Deprecation} 与 {@code Link: rel="successor-version"} 头。
     *
     * <p><b>不要</b>再往这个前缀下新加接口——新接口一律挂 {@link #V1}。</p>
     */
    public static final String LEGACY = "/api";

    /**
     * 服务间内部接口前缀（当前仅 {@code /internal/review}）。
     *
     * <p><b>为什么不参与版本演进</b>：它的调用方是自家引擎，双侧同仓同版本发布，
     * 不存在「老客户端改不动」的问题；给它套一层版本只会让每次内部契约调整都多一份并存实现。
     * 它的演进约束由 {@code ContractVersion}（{@code schemaVersion}）负责，而不是 URL。</p>
     */
    public static final String INTERNAL = "/internal";

    private ApiVersion() {
    }
}
