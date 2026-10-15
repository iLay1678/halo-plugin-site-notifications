package top.ilay.sitenotifications;

import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/** 每个账户使用独立 ConfigMap；创建时间隔离同名新账户。 */
@Component
@RequiredArgsConstructor
final class DefaultWebhookConfigStore implements WebhookConfigStore {
    static final String LEGACY_SECRET_NAME = "site-notifications-webhook-config";
    static final String LEGACY_DATA_KEY = "users.json";
    static final String OWNER_LABEL = "site-notifications.halo.run/account";
    private final ReactiveExtensionClient client;

    @Override
    public Mono<WebhookConfig> fetch(String username) {
        return currentUser(username).flatMap(user -> client.fetch(ConfigMap.class, configMapName(user)))
            .filter(RecipientService::active).mapNotNull(DefaultWebhookConfigStore::configFrom);
    }

    @Override
    public Mono<Void> save(String username, String url, String method, String template,
        Map<String, String> headers) {
        return WebhookTemplate.validate(template)
            .then(Mono.defer(() -> currentUser(username)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("用户不存在或已删除")))
                .flatMap(user -> {
                    var config = new WebhookConfig(url == null || url.isBlank() ? null : url.trim(),
                        method,
                        template == null || template.isBlank() ? null : template,
                        headers);
                    return client.fetch(ConfigMap.class, configMapName(user))
                        .filter(RecipientService::active)
                        .flatMap(existing -> {
                            var previous = configFrom(existing);
                            var destination = config.url() == null ? previous.url() : config.url();
                            return WebhookUrlValidator.validate(destination).then(Mono.defer(() -> {
                                existing.setData(data(new WebhookConfig(destination, config.method(),
                                    config.template(), config.headers())));
                                return client.update(existing);
                            }));
                        })
                        .switchIfEmpty(Mono.defer(() -> WebhookUrlValidator.validate(config.url())
                            .then(client.create(newConfigMap(user, config))))).then();
                }))).retryWhen(conflictRetry());
    }

    @Override
    public Mono<Void> clear(String username) {
        return currentUser(username).flatMap(this::clear);
    }

    @Override
    public Mono<Void> clear(User user) {
        return Mono.defer(() -> client.fetch(ConfigMap.class, configMapName(user))
            .flatMap(client::delete).then()).retryWhen(conflictRetry());
    }

    @Override
    public Mono<Void> migrateLegacy() {
        return client.fetch(Secret.class, LEGACY_SECRET_NAME).flatMap(secret -> {
            var entries = legacyEntries(secret);
            return Flux.fromIterable(entries.entrySet()).concatMap(entry ->
                currentUser(entry.getKey())
                    .flatMap(user -> migrateUser(user, legacyConfig(entry.getValue()))
                        .then(removeLegacyEntry(entry.getKey())))
                )
                .then(removeEmptyLegacy());
        });
    }

    private Mono<Void> migrateUser(User user, WebhookConfig config) {
        return client.fetch(ConfigMap.class, configMapName(user))
            .switchIfEmpty(Mono.defer(() -> client.create(newConfigMap(user, config))))
            .then();
    }

    @Override
    public Mono<Void> clearLegacy(String username) {
        return removeLegacyEntry(username);
    }

    private Mono<Void> removeLegacyEntry(String username) {
        return Mono.defer(() -> client.fetch(Secret.class, LEGACY_SECRET_NAME).flatMap(secret -> {
            var entries = legacyEntries(secret);
            entries.remove(username);
            if (secret.getStringData() == null) secret.setStringData(new HashMap<>());
            secret.getStringData().put(LEGACY_DATA_KEY, WebhookJson.write(entries));
            return client.update(secret).then();
        })).retryWhen(conflictRetry());
    }

    private Mono<Void> removeEmptyLegacy() {
        return client.fetch(Secret.class, LEGACY_SECRET_NAME).filter(secret -> legacyEntries(secret).isEmpty())
            .flatMap(secret -> {
                run.halo.app.extension.ExtensionUtil.removeFinalizers(secret.getMetadata(),
                    java.util.Set.of(run.halo.app.extension.MetadataUtil.SYSTEM_FINALIZER));
                return client.update(secret).flatMap(client::delete);
            }).then();
    }

    private Mono<User> currentUser(String username) {
        return client.fetch(User.class, username).filter(RecipientService::active);
    }

    static String configMapName(User user) {
        if (user.getMetadata().getCreationTimestamp() == null) {
            throw new IllegalArgumentException("用户缺少创建时间，无法绑定 Webhook 配置");
        }
        try {
            var identity = user.getMetadata().getName() + "\n" + user.getMetadata().getCreationTimestamp();
            return "site-notifications-webhook-" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ConfigMap newConfigMap(User user, WebhookConfig config) {
        var map = new ConfigMap();
        map.setMetadata(new Metadata());
        map.getMetadata().setName(configMapName(user));
        map.getMetadata().setLabels(Map.of(OWNER_LABEL,
            configMapName(user).substring("site-notifications-webhook-".length(),
                "site-notifications-webhook-".length() + 63)));
        map.setData(data(config));
        return map;
    }

    private static Map<String, String> data(WebhookConfig config) {
        var data = new HashMap<String, String>();
        data.put("url", config.url());
        data.put("method", config.method());
        if (config.template() != null) data.put("template", config.template());
        if (!config.headers().isEmpty()) data.put("headers", WebhookJson.write(config.headers()));
        return data;
    }

    private static WebhookConfig configFrom(ConfigMap map) {
        var data = map.getData();
        if (data == null || data.get("url") == null || data.get("url").isBlank()) return null;
        return new WebhookConfig(data.get("url"), data.get("method"),
            data.get("template"), data.get("headers") == null ? Map.of()
                : WebhookJson.read(data.get("headers"), new TypeReference<Map<String, String>>() {}));
    }

    private static Map<String, Object> legacyEntries(Secret secret) {
        var value = secret.getStringData() == null ? null : secret.getStringData().get(LEGACY_DATA_KEY);
        return value == null || value.isBlank() ? new HashMap<>()
            : new HashMap<>(WebhookJson.read(value, new TypeReference<Map<String, Object>>() {}));
    }

    private static WebhookConfig legacyConfig(Object value) {
        if (value instanceof String url && !url.isBlank()) return new WebhookConfig(url, "POST", null, Map.of());
        if (value instanceof Map<?, ?> config && config.get("url") instanceof String url && !url.isBlank()) {
            return new WebhookConfig(url, "POST", config.get("template") instanceof String text ? text : null,
                Map.of());
        }
        throw new IllegalArgumentException("历史 Webhook 数据格式无效");
    }

    private static Retry conflictRetry() {
        return Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
