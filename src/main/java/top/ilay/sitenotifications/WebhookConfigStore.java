package top.ilay.sitenotifications;

import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;

/** 保存各用户的 Webhook 地址和请求正文模板。 */
interface WebhookConfigStore {
    Mono<WebhookConfig> fetch(String username);

    Mono<Void> save(String username, String url, String template);

    Mono<Void> clear(String username);

    Mono<Void> clear(User user);

    Mono<Void> migrateLegacy();

    Mono<Void> clearLegacy(String username);
}

record WebhookConfig(String url, String template) {
}
