package com.turbofeed.benchmark;

/**
 * 极简 JSON 字段提取（<b>只为压测端，不为通用</b>）。
 *
 * <p>压测模块刻意不引 Jackson：压测机必须比被测服务更轻，反序列化带来的反射与临时对象
 * 会成为压测端自己的瓶颈。这里只需要两个字段——统一返回结构的 {@code code}、
 * 登录结果的 {@code accessToken}，手写提取足够，且离线构建不拉包。</p>
 *
 * <p><b>能力边界（明确声明，避免被误用）</b>：</p>
 * <ul>
 *   <li>只做「找第一个同名成员」，不区分嵌套层级。对本项目的契约是安全的：
 *       {@code code} 是 {@code Result} 的第一个成员，{@code accessToken} 全局唯一。</li>
 *   <li>不做完整校验：JSON 格式错误时返回默认值 / {@code null}，由调用方按失败处理。</li>
 *   <li>支持字符串转义（引号、反斜杠、换行、制表符，以及「反斜杠 + u + 四位十六进制」序列），
 *       因为 content 里可能带引号。</li>
 * </ul>
 */
public final class JsonFields {

    private JsonFields() {
    }

    /**
     * 取整数字段（如 {@code "code":0}）。
     *
     * @return 字段值；不存在或无法解析时返回 {@code defaultValue}
     */
    public static int intField(String json, String name, int defaultValue) {
        int valueStart = valueStart(json, name);
        if (valueStart < 0) {
            return defaultValue;
        }
        int i = valueStart;
        int len = json.length();
        boolean negative = false;
        if (i < len && json.charAt(i) == '-') {
            negative = true;
            i++;
        }
        long acc = 0;
        boolean any = false;
        while (i < len) {
            char c = json.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            acc = acc * 10 + (c - '0');
            any = true;
            i++;
            if (acc > Integer.MAX_VALUE) {
                return defaultValue; // 溢出：不是本模块关心的数字
            }
        }
        if (!any) {
            return defaultValue;
        }
        return negative ? (int) -acc : (int) acc;
    }

    /**
     * 取字符串字段（如 {@code "accessToken":"eyJ..."}），已解转义。
     *
     * @return 字段值；不存在 / 值为 null / 非字符串时返回 {@code null}
     */
    public static String stringField(String json, String name) {
        int valueStart = valueStart(json, name);
        if (valueStart < 0 || valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        int i = valueStart + 1;
        int len = json.length();
        while (i < len) {
            char c = json.charAt(i);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                i++;
                continue;
            }
            if (i + 1 >= len) {
                return null; // 截断的转义序列
            }
            char esc = json.charAt(i + 1);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 5 >= len) {
                        return null;
                    }
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    i += 6;
                    continue;
                }
                default -> sb.append(esc); // 未知转义：原样保留，不抛
            }
            i += 2;
        }
        return null; // 字符串未闭合
    }

    /**
     * 定位「名为 {@code name} 的成员」的值起始位置（跳过 {@code :} 与其后空白）。
     *
     * <p>只认对象成员位置（前一个非空白字符是 {@code \{} 或 {@code ,}），避免命中
     * 字符串值内部恰好出现的 {@code "code"} 字样——探针在评论内容里塞
     * {@code "code":0} 时不会误判成功。</p>
     *
     * @return 值起始下标；未找到返回 {@code -1}
     */
    private static int valueStart(String json, String name) {
        if (json == null || name == null || json.isEmpty()) {
            return -1;
        }
        String needle = "\"" + name + "\"";
        int from = 0;
        while (true) {
            int idx = json.indexOf(needle, from);
            if (idx < 0) {
                return -1;
            }
            int prev = idx - 1;
            while (prev >= 0 && Character.isWhitespace(json.charAt(prev))) {
                prev--;
            }
            boolean isMember = prev >= 0 && (json.charAt(prev) == '{' || json.charAt(prev) == ',');
            if (!isMember) {
                from = idx + needle.length();
                continue;
            }
            int i = idx + needle.length();
            int len = json.length();
            while (i < len && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= len || json.charAt(i) != ':') {
                from = idx + needle.length();
                continue;
            }
            i++;
            while (i < len && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            return i;
        }
    }
}
