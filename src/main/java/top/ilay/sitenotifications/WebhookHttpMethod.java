package top.ilay.sitenotifications;

import java.util.Locale;
import java.util.Set;

final class WebhookHttpMethod {
    static final String DEFAULT = "POST";
    static final Set<String> SUPPORTED = Set.of("POST", "PUT", "PATCH", "GET", "DELETE");

    private WebhookHttpMethod() {
    }

    static String normalize(String value) {
        var method = value == null || value.isBlank() ? DEFAULT : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(method)) throw new IllegalArgumentException("不支持的 Webhook 请求方式");
        return method;
    }
}
