package com.turbofeed.gateway.service.event.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/**
 * Canal 连接器工厂（从 {@link CanalCdcForwarder} 拆出，便于单测 mock 连接器）。
 *
 * <p>生产实现用 {@link CanalConnectors#newSingleConnector} 连单个 Canal Server；
 * 单测可覆写 {@link #create()} 返回 mock connector，避免依赖真实 Canal 进程。</p>
 */
@Configuration
public class CanalConnectorFactory {

    @Value("${turbofeed.outbox.cdc.canal.host:127.0.0.1}")
    private String host;

    @Value("${turbofeed.outbox.cdc.canal.port:11111}")
    private int port;

    @Value("${turbofeed.outbox.cdc.canal.destination:example}")
    private String destination;

    /** Canal server 默认无鉴权；如开启需配置 username/password。 */
    @Value("${turbofeed.outbox.cdc.canal.username:}")
    private String username;

    @Value("${turbofeed.outbox.cdc.canal.password:}")
    private String password;

    public CanalConnector create() {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;
        return CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port), destination, user, pass);
    }
}
