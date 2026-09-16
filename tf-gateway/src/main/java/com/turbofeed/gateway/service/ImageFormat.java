package com.turbofeed.gateway.service;

/**
 * 上传图片的真实格式：以文件头 Magic Number（字节签名）判定，不信任扩展名 / Content-Type。
 *
 * <p>白名单刻意<b>不收录 SVG</b>——SVG 可内嵌脚本存在 XSS 风险，前端 {@code accept=image/*}
 * 会放行，必须在此处拒绝。</p>
 */
public enum ImageFormat {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F'};
    /** WEBP 位于 RIFF 头之后偏移 8 字节处：RIFF????WEBP。 */
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** 存储扩展名（对象 key 命名用）。 */
    public String extension() {
        return extension;
    }

    /** HTTP Content-Type（对象存储写入时设置，决定浏览器解析方式）。 */
    public String contentType() {
        return contentType;
    }

    /**
     * 按 Content-Type 声明值映射到白名单格式（预签名直传场景：客户端上报 MIME，服务端据此决定对象名后缀）。
     *
     * <p><b>与 {@link #detect(byte[])} 的分工</b>：本方法只做「声明值 → 枚举」的映射，
     * <b>不构成任何信任</b>——真实格式在完成阶段由服务端读对象文件头用 {@code detect} 复检
     * （信文件头不信客户端声明）。伪造 contentType 只能骗过对象名后缀，骗不过复检。</p>
     *
     * <p>容忍 {@code "image/png; charset=utf-8"} 这类带参数的写法（只取分号前的主类型并忽略大小写）；
     * 无法识别（含 SVG、空值）返回 {@code null}，由调用方拒绝。</p>
     */
    public static ImageFormat fromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.trim().toLowerCase(java.util.Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        for (ImageFormat format : values()) {
            if (format.contentType.equals(normalized)) {
                return format;
            }
        }
        return null;
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
