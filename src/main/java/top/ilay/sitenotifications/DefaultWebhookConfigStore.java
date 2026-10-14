package top.ilay.sitenotifications;

import static run.halo.app.extension.MetadataUtil.SYSTEM_FINALIZER;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.utils.JsonUtils;

@Component
@RequiredArgsConstructor
final class DefaultWebhookConfigStore implements WebhookConfigStore {
    static final String SECRET_NAME = "site-notifications-webhook-config";
    static final String DATA_KEY = "users.json";

    private final ReactiveExtensionClient client;

    @Override
    public Mono<String> fetch(String username) {
        return readUsers().mapNotNull(users -> users.get(username)).filter(value -> !value.isBlank());
    }

    @Override
    public Mono<Void> save(String username, String url) {
        return WebhookUrlValidator.validate(url)
            .then(Mono.defer(() -> readSecretOrCreate().flatMap(secret -> {
                var users = usersFrom(secret);
                users.put(username, url.trim());
                return write(secret, users);
            })).retryWhen(conflictRetry()));
    }

    @Override
    public Mono<Void> clear(String username) {
        return Mono.defer(() -> client.fetch(Secret.class, SECRET_NAME)
            .flatMap(secret -> {
                var users = usersFrom(secret);
                users.remove(username);
                return write(secret, users);
            })).retryWhen(conflictRetry());
    }

    private static Retry conflictRetry() {
        return Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private Mono<Map<String, String>> readUsers() {
        return client.fetch(Secret.class, SECRET_NAME)
            .map(this::usersFrom)
            .defaultIfEmpty(Map.of());
    }

    private Mono<Secret> readSecretOrCreate() {
        return client.fetch(Secret.class, SECRET_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                var secret = new Secret();
                secret.setMetadata(new Metadata());
                secret.getMetadata().setName(SECRET_NAME);
                secret.getMetadata().setFinalizers(Set.of(SYSTEM_FINALIZER));
                secret.setStringData(new HashMap<>());
                return client.create(secret)
                    .onErrorResume(error -> client.fetch(Secret.class, SECRET_NAME)
                        .switchIfEmpty(Mono.error(error)));
            }));
    }

    private Map<String, String> usersFrom(Secret secret) {
        var data = secret.getStringData();
        if (data == null || data.get(DATA_KEY) == null || data.get(DATA_KEY).isBlank()) {
            return new HashMap<>();
        }
        return new HashMap<>(JsonUtils.jsonToObject(data.get(DATA_KEY), new TypeReference<Map<String, String>>() {}));
    }

    private Mono<Void> write(Secret secret, Map<String, String> users) {
        if (secret.getStringData() == null) {
            secret.setStringData(new HashMap<>());
        }
        secret.getStringData().put(DATA_KEY, JsonUtils.objectToJson(users));
        return client.update(secret).then();
    }
}
