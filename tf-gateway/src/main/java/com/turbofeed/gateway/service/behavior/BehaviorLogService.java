package com.turbofeed.gateway.service.behavior;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.BehaviorLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 异常行为日志服务（P2-2，抖音式审核闭环数据底座）。
 *
 * <p>在举报 / 评论异常 / 处置等写入点后调用 {@link #record}，把行为追加到 behavior_log 单表。
 * <b>fail-open</b>：catch 后仅记日志、绝不抛异常、不阻断审核主流程——行为日志是旁路审计/分析数据，
 * 丢失不影响审核正确性（上游信号仍由 P2-1 的 Redis KV 与 MySQL 主表保障）。</p>
 *
 * <p>开关由 {@link MediaProperties.BehaviorLog#isEnabled()} 控制（默认 true）；置 false 可一键停用写入（排障用）。</p>
 */
@Service
public class BehaviorLogService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(BehaviorLogService.class);

    private final BehaviorLogRepository repository;
    private final MediaProperties mediaProperties;

    public BehaviorLogService(BehaviorLogRepository repository, MediaProperties mediaProperties) {
        this.repository = repository;
        this.mediaProperties = mediaProperties;
    }

    /**
     * 记录一条行为（mediaId / detail 允许为 null）。
     *
     * @param action 行为类型：REPORT / COMMENT_ANOMALY / DISPOSITION
     */
    public void record(long userId, String mediaId, String action, String detail) {
        if (!mediaProperties.getBehaviorLog().isEnabled()) {
            return;
        }
        try {
            repository.insert(userId, mediaId, action, detail);
        } catch (Exception e) {
            log.warn("写行为日志失败（fail-open，不影响主流程）: userId={}, mediaId={}, action={}, {}", userId, mediaId, action, e.getMessage());
        }
    }
}
