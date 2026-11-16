package com.turbofeed.gateway.service.counter;

import com.turbofeed.gateway.config.CounterProperties;
import com.turbofeed.gateway.security.InternalTokenInterceptor;
import com.turbofeed.shared.model.CounterIncrementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 计数增量 HTTP 兜底发布器（默认，{@code turbofeed.mq.enabled=false/缺失} 且
 * {@code turbofeed.counter.service.enabled=true} 时激活）。
 *
 * <p>调 tf-counter {@code POST /internal/counter/incr}，镜像 {@code FeedEngineClient} 的防腐层写法
 * （显式超时 + 内部令牌 + 失败 fail-open）。本地无 tf-counter 时开关默认关，本 Bean 不创建。</p>
 *
 * <p><b>突发档（优化项①）</b>：MQ 分支发送失败也会复用本类做直投兜底，因此额外提供
 * {@link #tryIncr} 返回投递结果，让上层决定是否需要进入本地重试队列。
 * 接口 {@link CounterEventPublisher#incr} 仍保持 {@code void} + fail-open，不影响既有调用点。</p>
 */
public class HttpCounterEventPublisher implements CounterEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpCounterEventPublisher.class);
    private static final String PATH_INCR = "/internal/counter/incr";

    private final RestClient restClient;

    public HttpCounterEventPublisher(CounterProperties props, RestClient.Builder restClientBuilder,
                                      @Value("${turbofeed.internal.token:}") String internalToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) props.getReadTimeout().toMillis());
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(props.getBaseUrl()).requestFactory(requestFactory);
        if (internalToken != null && !internalToken.isBlank()) {
            builder = builder.defaultHeaders(headers -> headers.set(InternalTokenInterceptor.HEADER, internalToken));
        }
        this.restClient = builder.build();
        log.info("计数增量发布器(HTTP)初始化: baseUrl={}, connectTimeout={}, readTimeout={}, internalToken={}",
                props.getBaseUrl(), props.getConnectTimeout(), props.getReadTimeout(),
                internalToken == null || internalToken.isBlank() ? "未配置(fail-open)" : "已配置");
    }

    @Override
    public void incr(String postId, String type, int delta) {
        tryIncr(postId, type, delta);
    }

    /**
     * 与 {@link #incr} 相同的投递动作，但返回结果供 MQ 分支做降级决策。
     *
     * @return true = tf-counter 已受理；false = 本次失败（上层应重试 / 留痕）
     */
    public boolean tryIncr(String postId, String type, int delta) {
        try {
            restClient.post()
                    .uri(PATH_INCR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(new CounterIncrementEvent(postId, type, delta)))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("计数增量投递 tf-counter(HTTP)失败（fail-open，不影响点赞主流程）: postId={}, type={}, delta={}, {}",
                    postId, type, delta, e.getMessage());
            return false;
        }
    }
}
