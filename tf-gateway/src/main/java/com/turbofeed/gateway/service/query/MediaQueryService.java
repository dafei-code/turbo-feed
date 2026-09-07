package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.review.MediaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 媒体查询服务（读侧）：提供「用户 → 其上传内容」的列表与单条状态查询。
 *
 * <p><b>职责演变</b>：此前维护进程内「用户维度倒排索引」({@code userIndex})，重启即丢失，
 * 现已由 {@link MediaJdbcRepository} 对 media 表（按 user_id 分片）的查询取代，
 * 单一事实源、与审核状态同表，无需双写、无内存态。</p>
 *
 * <p><b>身份隔离</b>：userId 来自 JWT（{@code UserContextHolder.requireUserId()}），
 * 不经方法入参、客户端无法传入他人 ID 越权查看。列表/状态查询均带 user_id 分片键，
 * 精准命中单分片，不广播。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQueryService {

    private final MediaJdbcRepository mediaRepository;

    /**
     * 查询指定用户上传的内容列表（按上传时间倒序，分页）。
     *
     * @param userId        归属用户（来自 JWT，非客户端可控）
     * @param statusFilter  状态过滤（null = 不过滤，供个人中心全量/统计使用）
     * @param page          页码（从 0 开始）
     * @param size          单页条数（≤0 兜底为 50）
     * @return 该用户的内容列表（当前页），从未上传过则返回空列表
     */
    public List<MediaItem> listByUser(String userId, MediaStatus statusFilter, int page, int size) {
        int limit = size <= 0 ? 50 : size;
        long offset = (long) Math.max(page, 0) * limit;
        return mediaRepository.listByUser(Long.parseLong(userId), statusFilter, limit, offset);
    }

    /**
     * 公域推荐流（占位实现）：透传 {@link MediaJdbcRepository#listApprovedGlobal} 的分页查询。
     * 仅用于演示/小数据量，生产须由推荐服务 + 异构索引取代（见 changelog 0017）。
     *
     * @param page 页码（从 0 开始）
     * @param size 单页条数（≤0 兜底为 20）
     */
    public List<MediaItem> listRecommended(int page, int size) {
        int limit = size <= 0 ? 20 : size;
        long offset = (long) Math.max(page, 0) * limit;
        return mediaRepository.listApprovedGlobal(limit, offset);
    }

    /**
     * 查询单条内容的当前审核状态。
     *
     * <p>查不到时返回 {@link MediaStatus#PENDING} 而非 null：内容已受理但审核事件
     * 尚未到达（异步模式下存在该窗口），对前端而言就是「处理中」，语义正确且避免
     * 前端处理 null 分支。</p>
     *
     * @param mediaId 内容唯一标识
     * @param userId  归属用户（分片键，保证按单分片精准查询）
     * @return 当前状态，未查到时按 PENDING 处理
     */
    public MediaStatus statusOf(String mediaId, String userId) {
        MediaStatus status = mediaRepository.getStatus(mediaId, Long.parseLong(userId));
        return status != null ? status : MediaStatus.PENDING;
    }
}
