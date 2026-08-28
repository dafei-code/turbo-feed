package com.turbofeed.gateway.service.processing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 图片处理链（Decorator 模式的链容器）：按 {@code @Order} 顺序执行全部装饰器，再重编码输出。
 *
 * <p>执行模型：{@code 解码(ImageIO.read) -> 逐装饰器处理 -> 重编码(ImageIO.write)}。
 * 重编码同时承担 EXIF 剥离（JDK JPEG 编码器不写回元数据）与统一质量输出。
 * 处理器列表由 Spring 注入全部 {@link ImageProcessor} 实现并按 {@code @Order} 排序，
 * 新增装饰器只需加一个 {@code @Component}，本类零改动。</p>
 *
 * <p>调用约定：仅对 jpg / png 执行（webp 无 ImageIO 编解码、gif 保留动图，由各装饰器
 * {@code supports} 拒绝）；处理失败由上层捕获并降级使用原图，绝不因处理失败阻断上传。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageProcessingChain {

    /** Spring 注入全部处理器实现，并按 {@code @Order} 升序排列（链顺序由注解控制）。 */
    private final List<ImageProcessor> processors;

    /**
     * 执行图片处理链，返回重编码后的字节。
     *
     * @param source    原始图片字节（已通过 Magic Number 校验）
     * @param extension 图片扩展名（jpg / png）
     * @return 处理后的图片字节
     * @throws IOException 解码 / 重编码失败（由调用方降级处理）
     */
    public byte[] process(byte[] source, String extension) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("ImageIO 无法解码图片: ext=" + extension);
        }
        BufferedImage current = image;
        for (ImageProcessor processor : processors) {
            if (processor.supports(extension)) {
                current = processor.process(current, extension);
                log.debug("处理链执行: name={}, ext={}", processor.name(), extension);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length);
        if (!ImageIO.write(current, extension, out)) {
            throw new IOException("无匹配的 ImageWriter: ext=" + extension);
        }
        return out.toByteArray();
    }
}
