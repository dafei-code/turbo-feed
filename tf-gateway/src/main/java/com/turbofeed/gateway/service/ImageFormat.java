package com.turbofeed.gateway.service;

/**
 * 上传图片的真实格式：以文件头 Magic Number（字节签名）判定，不信任扩展名 / Content-Type。
 *
 * <p>白名单刻意<b>不收录 SVG</b>——SVG 可内嵌脚本存在 XSS 风险，前端 {@code accept=image/*}
 * 会放行，必须在此处拒绝。</p>
 */
public enum ImageFormat {

    JPEG("jpg"),
    PNG("png"),
    GIF("gif"),
    WEBP("webp");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F'};
    /** WEBP 位于 RIFF 头之后偏移 8 字节处：RIFF????WEBP。 */
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    private final String extension;

    ImageFormat(String extension) {
        this.extension = extension;
    }

    /** 存储扩展名（对象 key 命名用）。 */
    public String extension() {
        return extension;
    }

    /** 判定文件头对应的真实格式；无法识别（含 SVG / 伪造扩展名）返回 null。 */
    public static ImageFormat detect(byte[] header) {
        if (startsWith(header, 0, JPEG_MAGIC)) {
            return JPEG;
        }
        if (startsWith(header, 0, PNG_MAGIC)) {
            return PNG;
        }
        if (startsWith(header, 0, GIF_MAGIC)) {
            return GIF;
        }
        if (startsWith(header, 0, RIFF_MAGIC) && startsWith(header, 8, WEBP_MAGIC)) {
            return WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] magic) {
        if (data.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
