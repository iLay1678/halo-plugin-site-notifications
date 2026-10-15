package top.ilay.sitenotifications;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class WebhookHeaders {
    private static final Pattern NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED = Set.of("host", "content-length", "transfer-encoding",
        "connection", "te", "trailer", "upgrade", "proxy-authorization", "proxy-connection", "expect");

    private WebhookHeaders() {
    }

    static Map<String, String> normalize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        if (headers.size() > 32) throw new IllegalArgumentException("Webhook 请求头不能超过 32 个");
        var result = new LinkedHashMap<String, String>();
        var names = new java.util.HashSet<String>();
        var bytes = new int[1];
        headers.forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Webhook 请求头名称无效");
            }
            var key = name.toLowerCase(Locale.ROOT);
            if (name.length() > 256 || RESERVED.contains(key) || !names.add(key)) {
                throw new IllegalArgumentException("Webhook 请求头名称重复或由客户端管理");
            }
            if (value == null || value.length() > 4096
                || value.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
                throw new IllegalArgumentException("Webhook 请求头值无效");
            }
            bytes[0] += name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes[0] > 16 * 1024) throw new IllegalArgumentException("Webhook 请求头总大小不能超过 16 KiB");
            result.put(name, value);
        });
        return result;
    }
}
