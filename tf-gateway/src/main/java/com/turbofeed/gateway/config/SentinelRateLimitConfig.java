package com.turbofeed.gateway.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 上传限流规则：启动时从 {@link MediaProperties} 读阈值注册到规则管理器。
 *
 * <p>两条规则，全部作用于资源 {@code media:upload}（埋点为
 * {@code MediaUploadService#upload} 上的 {@code @SentinelResource} 注解，
 * SentinelResourceAspect 代理执行——注解默认关闭 {@code entryType=OUT}，
 * Web 入口流量已在注解上显式声明为 {@code EntryType.IN}）：</p>
 *
 * <ol>
 *   <li><b>并发线程数</b>（FLOW_GRADE_THREAD）——上传是 IO 型接口，慢存储（MinIO 抖动）
 *       时 QPS 规则防不住线程堆积，并发上限才是进程保护的第一道闸；</li>
 *   <li><b>单机 QPS</b>（FLOW_GRADE_QPS）——防总量刷爆磁盘带宽。</li>
 * </ol>
 *
 * <p><b>用户维度不在本层</b>：注解模式的热点参数（ParamFlowRule）只能取方法签名参数
 * {@code (files, requestId)}，而身份 userId 在 ThreadLocal 中——不为限流把 userId 塞回
 * 方法签名，故用户级限流整体移交 Redis
 * {@code com.turbofeed.gateway.service.ratelimit.UploadRateLimiter}
 * （阈值 {@code turbofeed.media.rate-limit.per-user}），机器维度与用户维度各自分工。</p>
 *
 * <p>阈值全部外部化（turbofeed.media.rate-limit.*），调整只改配置不发版。
 * 当前规则代码注册（克隆即跑）；接入 Nacos 后可换 push 数据源动态下发，此处结构不变。</p>
 *
 * <p>边界说明：Sentinel 只管频次与并发维度；体积维度（带宽总量）由网关层
 * Nginx limit_req + client_max_body_size 兜底，属部署动作不进代码。
 * 集群流控（Token Server）在多实例部署后再评估，单机规则集群下各实例独立计数，当前够用。</p>
 */
@Configuration
public class SentinelRateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelRateLimitConfig.class);

    /** 上传接口资源名，与 MediaUploadService#upload 的 @SentinelResource value 保持一致（单一事实源）。 */
    public static final String UPLOAD_RESOURCE = "media:upload";

    private final MediaProperties properties;

    public SentinelRateLimitConfig(MediaProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initRules() {
        MediaProperties.RateLimit limit = properties.getRateLimit();

        FlowRule threadRule = new FlowRule(UPLOAD_RESOURCE);
        threadRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        threadRule.setCount(limit.getThread());

        FlowRule qpsRule = new FlowRule(UPLOAD_RESOURCE);
        qpsRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        qpsRule.setCount(limit.getQps());
        FlowRuleManager.loadRules(List.of(threadRule, qpsRule));

        log.info("Sentinel 上传限流规则已加载: resource={}, thread={}, qps={}, perUser(redis)={}",
                UPLOAD_RESOURCE, limit.getThread(), limit.getQps(), limit.getPerUser());
    }
}
