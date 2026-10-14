package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.extension.Metadata;
import java.util.HashMap;

class DefaultWebhookConfigStoreTest {
    @Test
    void storesUsersIndependentlyInSecret() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var storedSecret = new AtomicReference<Secret>();
        when(client.fetch(eq(Secret.class), eq(DefaultWebhookConfigStore.SECRET_NAME)))
            .thenAnswer(invocation -> Mono.justOrEmpty(storedSecret.get()));
        when(client.create(any(Secret.class))).thenAnswer(invocation -> {
            var secret = invocation.getArgument(0, Secret.class);
            storedSecret.set(secret);
            return Mono.just(secret);
        });
        when(client.update(any(Secret.class))).thenAnswer(invocation -> {
            var secret = invocation.getArgument(0, Secret.class);
            storedSecret.set(secret);
            return Mono.just(secret);
        });
        var store = new DefaultWebhookConfigStore(client);

        store.save("alice", "https://example.com/alice").block();
        store.save("bob", "https://example.com/bob").block();

        assertEquals("https://example.com/alice", store.fetch("alice").block());
        assertEquals("https://example.com/bob", store.fetch("bob").block());
        assertNull(store.fetch("unknown").block());
        store.clear("alice").block();
        assertNull(store.fetch("alice").block());
        assertEquals("https://example.com/bob", store.fetch("bob").block());
    }

    @Test
    void refetchesLatestUsersAfterAnOptimisticLockConflict() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var original = secret("{}");
        var latest = secret("{\"bob\":\"https://8.8.8.8/bob\"}");
        var stored = new AtomicReference<>(original);
        when(client.fetch(Secret.class, DefaultWebhookConfigStore.SECRET_NAME))
            .thenAnswer(invocation -> Mono.just(stored.get()));
        var firstUpdate = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(client.update(any(Secret.class))).thenAnswer(invocation -> {
            if (firstUpdate.getAndSet(false)) {
                stored.set(latest);
                return Mono.error(new OptimisticLockingFailureException("版本冲突"));
            }
            stored.set(invocation.getArgument(0, Secret.class));
            return Mono.just(stored.get());
        });
        var store = new DefaultWebhookConfigStore(client);
        store.save("alice", "https://8.8.8.8/alice").block();
        assertEquals("https://8.8.8.8/alice", store.fetch("alice").block());
        assertEquals("https://8.8.8.8/bob", store.fetch("bob").block());
    }

    private static Secret secret(String users) {
        var secret = new Secret();
        secret.setMetadata(new Metadata());
        secret.getMetadata().setName(DefaultWebhookConfigStore.SECRET_NAME);
        secret.setStringData(new HashMap<>());
        secret.getStringData().put(DefaultWebhookConfigStore.DATA_KEY, users);
        return secret;
    }
}
