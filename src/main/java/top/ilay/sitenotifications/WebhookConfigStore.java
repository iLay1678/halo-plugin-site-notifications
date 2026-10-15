package top.ilay.sitenotifications;

import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import java.util.Map;

/** 保存各用户的 Webhook 地址、请求方法、请求头和正文模板。 */
interface WebhookConfigStore {
    Mono<WebhookConfig> fetch(String username);

    Mono<Void> save(String username, String url, String method, String template, Map<String, String> headers);

    default Mono<Void> save(String username, String url, String template) {
        return save(username, url, "POST", template, Map.of());
    }

    Mono<Void> clear(String username);

    Mono<Void> clear(User user);

    Mono<Void> migrateLegacy();

    Mono<Void> clearLegacy(String username);
}

record WebhookConfig(String url, String method, String template, Map<String, String> headers) {
    WebhookConfig {
        method = WebhookHttpMethod.normalize(method);
        headers = Map.copyOf(WebhookHeaders.normalize(headers));
    }
    WebhookConfig(String url, String template) {
        this(url, "POST", template, Map.of());
    }
}
