package top.ilay.sitenotifications;

import reactor.core.publisher.Mono;

/** 保存各用户的 Webhook 目标地址。 */
interface WebhookConfigStore {
    Mono<String> fetch(String username);

    Mono<Void> save(String username, String url);

    Mono<Void> clear(String username);
}
