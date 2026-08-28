package com.turbofeed.gateway.service.processing;

import java.awt.image.BufferedImage;

/**
 * 图片处理步骤（Decorator 模式：装饰器接口）。
 *
 * <p>多个实现按 {@code @Order} 组成处理链（{@code ImageProcessingChain}），
 * 链式执行等价于装饰器逐层嵌套：{@code new Thumbnail(new ExifCleaner(original))}。
 * 新增处理（压缩 / 水印 / 模糊人脸等）只需新增实现类，链与上传主流程零改动——这是
 * 装饰器模式的核心收益：<b>扩展开放、修改封闭</b>。</p>
 *
 * <p>实现注意：{@link #supports} 决定是否参与某格式（如 webp 无 JDK ImageIO 编解码、
 * gif 保留动图均不参与），避免处理链破坏无法处理或不应处理的格式。</p>
 */
public interface ImageProcessor {

    /** 处理器名称（日志 / 监控维度）。 */
    String name();

    /**
     * 是否支持处理该格式（扩展名小写，如 jpg / png）。
     *
     * @param extension 图片扩展名
     */
    boolean supports(String extension);

    /**
     * 处理一帧图像，返回处理后的图像。
     *
     * @param source    原始图像（链上前序处理器的输出）
     * @param extension 图片扩展名
     */
    BufferedImage process(BufferedImage source, String extension);
}
