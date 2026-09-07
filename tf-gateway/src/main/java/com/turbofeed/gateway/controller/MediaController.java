package com.turbofeed.gateway.controller;

import com.turbofeed.gateway.security.UserContextHolder;
import com.turbofeed.gateway.service.MediaUploadService;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.query.MediaQueryService;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.result.Result;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 内容图片上传接口（UGC：用户上传、审核通过后前端展示）。
 *
 * <p>POST /api/media/upload，multipart/form-data，字段名 files，支持多文件。
 * 图片按用户隔离，每个用户只能管理自己上传的图片。<b>上传成功 ≠ 前端可见</b>，
 * 内容需经审核流转（PENDING → APPROVED / REJECTED），前端仅展示 APPROVED 的内容。</p>
 *
 * <p>分层职责：Controller 只做协议适配（参数绑定 / 统一返回结构），
 * 校验与存储在 {@code MediaUploadService}，真实落盘在 {@code MediaStorageClient}
 * 端口之后（当前为占位实现，MinIO 暂缓接入）。</p>
 *
 * <h3>上传接口设计要点（针对初版骨架提出的 8 个疑问的解答，按 UGC 大量用户口径）</h3>
 * <ol>
 *   <li><b>高并发 / 大数据量下，单表能否满足业务扩张？</b><br>
 *     文件实体不落 MySQL，存对象存储（MinIO/COS，S3 协议可横向扩展），DB 只存轻量元数据
 *     （id, user_id, url, status, created_at）。UGC 元数据增速 = 用户量 × 人均上传量，
 *     初期单表 + 「(user_id, created_at)」组合索引足够；到千万级再按 {@code user_id}
 *     分库分表。注意：<b>建表时就把 user_id 定为分片键候选</b>，避免后期拆分时改主键。</li>
 *
 *   <li><b>上传动作是同步还是异步？用户是否立即可见？</b><br>
 *     分两段：<b>文件落存储是同步的</b>（≤5MB 图片秒级完成，用户体验是"传完即确认"）；
 *     <b>可见性是异步的</b>——上传成功后内容进入 PENDING 待审态，经审核翻转后才对前端可见。
 *     若高峰期上传洪峰明显，将"写元数据 + 送审"改为经消息队列（RocketMQ）异步化，
 *     接口先返回受理结果，客户端凭 mediaId 查询处理进度（削峰 + 失败重试）。</li>
 *
 *   <li><b>是否需要审核？怎么做？</b><br>
 *     <b>必须审核</b>：UGC 内容面向公域展示，涉政涉黄风险不可裸奔。元数据加 {@code status}
 *     状态机：PENDING（上传待审）→ 机审（内容安全 API，秒级）→ APPROVED / REJECTED；
 *     机审通过再抽样人审兜底。前端查询一律过滤 {@code status = 'APPROVED'}，
 *     审核未通过的内容 URL 不对公域暴露（私有 bucket + 预签名 URL 可进一步收敛）。</li>
 *
 *   <li><b>大数据量下如何分库分表？</b><br>
 *     存储层靠对象存储天然无限扩展（MinIO Server Pool / 云 COS 自动扩容，代码不动）；
 *     元数据层按 {@code user_id} 哈希分片（同一用户的图片落在同一库，支撑"我的上传"查询），
 *     跨用户的公域 feed 流走 ES / 宽表异构，不扫分片库。不到千万级不动手分表，
 *     过早分表是过度设计。</li>
 *
 *   <li><b>文件限流怎么做？</b><br>
 *     两层：① 机器维度——Sentinel 注解限流（并发 20 + 单机 QPS 100，见 §3.1），
 *     保护进程不被慢存储拖垮、防总量刷爆带宽；② 用户维度——Redis {@code UploadRateLimiter}
 *     按 userId 时间窗限流（per-user=10，跨实例计数），避免单用户持续刷接口。
 *     洪峰削峰见第 2 点的异步化路径；multipart 大小上限在 application.yml 由框架层再兜底。</li>
 *
 *   <li><b>文件校验怎么做？</b><br>
 *     三重校验：① 类型白名单 jpg/png/gif/webp（UGC 场景必须白名单，绝不放开任意类型）；
 *     ② 用<b>文件头 Magic Number</b>（字节签名）校验，而非信任 Content-Type，
 *     防伪造扩展名——UGC 用户量大后伪造流量必然出现；③ <b>排除 SVG</b>（可内嵌脚本，
 *     XSS 风险，前端 accept=image/* 会放进来，后端必须挡）；④ 大小 ≤5MB、0 字节拒绝、
 *     单次 ≤9 张（限额均在 turbofeed.media.* 配置，调整不发版）。
 *     通过校验 ≠ 通过审核，校验解决"格式安全"，审核解决"内容安全"。</li>
 *
 *   <li><b>同一时间多次提交怎么拦截（幂等）？</b><br>
 *     前端按钮 loading 禁用防重复点击；后端按 {@code requestId}（客户端幂等键）去重：
 *     同一 requestId 短期内（如 5s）重复提交直接返回首次结果，结合 userId 防越权。
 *     异步化后幂等键同时充当任务去重键，防止队列里堆积重复任务。</li>
 *
 *   <li><b>网络抖动 / 宕机如何断点续传？</b><br>
 *     本期内容仅图片（≤5MB），整文件重传成本低，<b>不做断点续传</b>，失败提示用户手动重传。
 *     若后续放开大图或视频，演进方案：S3 MultipartUpload 分片上传 + 客户端记录已传分片
 *     checkpoint + 服务端 ListParts 恢复进度（README 演进章节展开）。</li>
 * </ol>
 *
 * <p><b>身份获取</b>：不依赖前端透传 {@code X-User-Id}（不安全且可伪造），方法签名亦
 * 不出现任何身份参数。身份由 {@code JwtAuthenticationFilter} 在请求入口验签
 * {@code Authorization: Bearer <JWT>} 后绑定到 {@code UserContextHolder}（ThreadLocal），
 * 业务层经 {@code requireUserId()} 获取；令牌缺失 / 失效统一返回 UNAUTHORIZED，
 * 请求结束 finally 清理上下文，杜绝线程池复用导致的身份串号。</p>
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaUploadService mediaUploadService;
    private final MediaQueryService mediaQueryService;

    /**
     * 内容图片上传（UGC，审核后展示）。
     *
     * <p>上传成功仅代表"文件已受理、进入待审核态"，审核通过后才会出现在前端。
     * 身份不经过方法签名：Filter 已将 JWT 解析的 userId 绑定到线程上下文，
     * 由 Service 层 {@code UserContextHolder.requireUserId()} 取用。</p>
     *
     * @param files     多个图片文件
     * @param requestId 客户端幂等键（可选，请求头 X-Request-Id；幂等去重落地时使用）
     * @return 统一返回结构，data 为上传成功后的图片 URL 列表（审核通过后生效）
     */
    @PostMapping("/upload")
    public Result<List<String>> upload(
            @RequestParam("files") MultipartFile[] files,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return Result.ok(mediaUploadService.upload(files, requestId));
    }

    /**
     * 查询当前用户上传的内容列表（按上传时间倒序，分页）。
     *
     * <p>补齐"只有写接口、没有读接口"的缺口：此前前端上传成功后无从得知自己传过什么，
     * 列表页只能展示硬编码的假数据。本接口返回每条内容的
     * {@link MediaItem#mediaId()}、{@link MediaItem#url()} 与
     * {@link MediaItem#status()}，前端据此渲染（个人中心可见全部状态，含待审/驳回）。</p>
     *
     * <p><b>P1.5 分页/过滤</b>：{@code status} 可按审核状态过滤（如只看 APPROVED），
     * {@code page}/{@code size} 分页，避免一次性回吐全部内容、也避免前端在内存里过滤状态。
     * 公域推荐流请走独立 {@code GET /api/feed/recommended}，不再复用本接口。</p>
     *
     * <p>身份来源同 {@link #upload}：从 {@link UserContextHolder#requireUserId()} 取，
     * 不经方法签名，客户端无法传他人 ID 越权查看；未携带有效令牌直接 UNAUTHORIZED。</p>
     *
     * @param page   页码（从 0 开始，默认 0）
     * @param size   单页条数（默认 50，≤0 兜底 50）
     * @param status 状态过滤（可选，null = 不过滤）
     * @return 当前用户的内容列表（当前页），从未上传过则返回空数组
     */
    @GetMapping("/mine")
    public Result<List<MediaItem>> mine(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "status", required = false) MediaStatus status) {
        String userId = UserContextHolder.requireUserId();
        return Result.ok(mediaQueryService.listByUser(userId, status, page, size));
    }

    /**
     * 查询单条内容的审核状态（客户端进度轮询用）。
     *
     * <p><b>为何用 query 参数而非路径参数</b>：{@code mediaId} 形如
     * {@code media/{userId}/{uuid}.{ext}}，<b>本身含斜杠</b>。Spring Boot 3 的
     * PathPattern 中 {@code {*mediaId}} 只能置于末尾，写成
     * {@code /{mediaId}/status} 则匹配不到带斜杠的值，且默认不会把 {@code %2F}
     * 解码为路径分隔符。改为 {@code ?mediaId=...} 可原样携带，客户端无需特殊处理。</p>
     *
     * @param mediaId 内容唯一标识（取自 {@link MediaItem#mediaId()}）
     * @return 当前审核状态；内容已受理但审核事件未到时返回 PENDING（处理中）
     */
    @GetMapping("/status")
    public Result<MediaStatus> status(@RequestParam("mediaId") String mediaId) {
        return Result.ok(mediaQueryService.statusOf(mediaId, UserContextHolder.requireUserId()));
    }
}
