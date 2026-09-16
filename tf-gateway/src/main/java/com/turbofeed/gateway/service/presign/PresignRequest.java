package com.turbofeed.gateway.service.presign;

import java.util.List;

/**
 * 预签名上传凭证申请（{@code POST /api/media/presign} 请求体）。
 *
 * <p>只携带<b>元数据</b>，不含字节流——字节由客户端拿到凭证后直传对象存储。
 * 因此本阶段的校验只覆盖「数量 / 声明大小 / 声明类型」三项可作弊的声明值，
 * 真实格式与真实大小在完成阶段由服务端读对象复检（信文件头不信客户端声明）。</p>
 *
 * @param files   待上传文件元数据（1..{@code turbofeed.media.max-batch-count} 项，有序）
 * @param caption 抖音式描述/标题（@用户 / #话题 / [image:idx:filename]，整帖共享；可空）
 */
public record PresignRequest(List<FileMeta> files, String caption) {

    /**
     * 单个文件的声明元数据。
     *
     * @param fileName    原始文件名（仅用于日志/审计，不参与对象名拼装）
     * @param size        声明字节数（服务端在申请阶段做上限初筛，完成阶段用 stat 复检）
     * @param contentType 浏览器侧 MIME（如 {@code image/jpeg}）；无法映射白名单格式即拒绝
     */
    public record FileMeta(String fileName, long size, String contentType) {
    }
}
