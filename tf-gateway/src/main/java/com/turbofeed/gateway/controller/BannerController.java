package com.turbofeed.gateway.controller;

import java.util.List;

import com.turbofeed.shared.result.ErrorCode;
import com.turbofeed.shared.result.Result;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 轮播图管理接口（骨架）。
 *
 * <p>对应前端 admin.html 的轮播图上传按钮：
 * POST /api/banner/upload，multipart/form-data，字段名 files，支持多文件。
 * 图片按用户隔离，每个用户只能看到/管理自己上传的图片。</p>
 */
@RestController
@RequestMapping("/api/banner")
public class BannerController {

    /**
     * 批量上传轮播图（归属当前用户）。
     *
     * <p>TODO: 具体逻辑自行实现。建议步骤：</p>
     * <ol>
     *   <li>校验文件非空、类型为图片、大小限制（不合法返回 {@code Result.fail(ErrorCode.UPLOAD_INVALID, ...)}）</li>
     *   <li>落盘或上传对象存储，得到访问 URL（建议路径带 userId 前缀，如 banner/{userId}/xxx.jpg）</li>
     *   <li>保存图片与用户的归属关系（表或元数据）</li>
     *   <li>返回该用户的图片 URL 列表（前端 uploadImages() 会消费 Result.data 用于轮播展示）</li>
     * </ol>
     *
     * <p>注意：X-User-Id 仅演示用。接入真实鉴权后应从登录态（token/session）中
     * 解析用户身份，禁止信任客户端直接传的用户 ID。</p>
     *
     * @param userId 当前用户 ID（演示阶段由前端请求头 X-User-Id 携带）
     * @param files  多个图片文件
     * @return 统一返回结构，data 为上传成功后的图片 URL 列表
     */
    @PostMapping("/upload")
    public Result<List<String>> upload(@RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
                                       @RequestParam("files") MultipartFile[] files) {
        // TODO: 具体上传逻辑由使用者实现（按 userId 隔离存储）
        // 成功：return Result.ok(urlList);
        // 失败：return Result.fail(ErrorCode.UPLOAD_INVALID, "仅支持 jpg/png/gif/webp");
        return Result.fail(ErrorCode.INTERNAL_ERROR, "上传逻辑未实现");
    }
}
