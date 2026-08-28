package com.turbofeed.gateway.service.processing;

import com.turbofeed.gateway.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 缩略图装饰器（链尾，{@code @Order(20)}）：长边超过阈值时等比缩放。
 *
 * <p>价值：手机原图动辄 4000×3000（约 12MP），直接下发徒增带宽与客户端解码开销；
 * 限制长边 ≤ {@code turbofeed.media.thumbnail-max-dimension}（默认 2048）可显著减小
 * 存储与分发成本。小于阈值时原样返回（零开销）。</p>
 *
 * <p><b>内存注意</b>：ImageIO 解码 + 缩放会将图像解压为像素数组（12MP ARGB ≈ 48MB），
 * 处理链默认关闭（{@code processing-enabled: false}），开启前请评估上传 QPS 与堆内存。</p>
 */
@Order(20)
@Component
@RequiredArgsConstructor
public class ThumbnailProcessor implements ImageProcessor {

    private final MediaProperties properties;

    @Override
    public String name() {
        return "thumbnail";
    }

    @Override
    public boolean supports(String extension) {
        return "jpg".equals(extension) || "png".equals(extension);
    }

    @Override
    public BufferedImage process(BufferedImage source, String extension) {
        long max = properties.getThumbnailMaxDimension();
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= max && height <= max) {
            return source;
        }
        double scale = Math.min(max / (double) width, max / (double) height);
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));
        // PNG 需保留透明通道；JPEG 不支持 alpha，用不透明类型
        int imageType = "png".equals(extension)
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(newWidth, newHeight, imageType);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }
}
