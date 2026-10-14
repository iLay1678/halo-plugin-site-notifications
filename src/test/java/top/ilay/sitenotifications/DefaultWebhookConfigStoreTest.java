package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.utils.JsonUtils;

class DefaultWebhookConfigStoreTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final Map<String, ConfigMap> maps = new HashMap<>();
    private final Map<String, User> users = new HashMap<>();
    private Secret legacy;

    private DefaultWebhookConfigStore store() {
        when(client.fetch(eq(User.class), anyString()))
            .thenAnswer(i -> Mono.defer(() -> Mono.justOrEmpty(users.get(i.getArgument(1)))));
        when(client.fetch(eq(ConfigMap.class), anyString()))
            .thenAnswer(i -> Mono.defer(() -> Mono.justOrEmpty(maps.get(i.getArgument(1)))));
        when(client.create(any(ConfigMap.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            var input = i.getArgument(0, ConfigMap.class);
            input.getMetadata().setVersion(1L);
            maps.put(input.getMetadata().getName(), input);
            return input;
        }));
        when(client.update(any(ConfigMap.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            var input = i.getArgument(0, ConfigMap.class);
            maps.put(input.getMetadata().getName(), input);
            return input;
        }));
        when(client.delete(any(ConfigMap.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            var input = i.getArgument(0, ConfigMap.class);
            maps.remove(input.getMetadata().getName());
            return input;
        }));
        when(client.fetch(Secret.class, DefaultWebhookConfigStore.LEGACY_SECRET_NAME))
            .thenAnswer(i -> Mono.defer(() -> Mono.justOrEmpty(legacy)));
        when(client.update(any(Secret.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            legacy = i.getArgument(0);
            return legacy;
        }));
        when(client.delete(any(Secret.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            var input = i.getArgument(0, Secret.class);
            legacy = null;
            return input;
        }));
        return new DefaultWebhookConfigStore(client);
    }

    @Test
    void isolatesUsersAndReturnsSavedTemplate() {
        users.put("alice", user("alice", "2026-01-01T00:00:00Z"));
        users.put("bob", user("bob", "2026-01-01T00:00:00Z"));
        var store = store();
        var template = "{\"text\":\"{{rawBody}}\"}";
        store.save("alice", "https://8.8.8.8/alice", template).block();
        store.save("bob", "https://8.8.8.8/bob", null).block();
        assertEquals(2, maps.size());
        assertEquals(new WebhookConfig("https://8.8.8.8/alice", template), store.fetch("alice").block());
        store.save("alice", null, "{\"title\":\"{{title}}\"}").block();
        assertEquals("https://8.8.8.8/alice", store.fetch("alice").block().url());
        store.clear("alice").block();
        assertNull(store.fetch("alice").block());
        assertEquals("https://8.8.8.8/bob", store.fetch("bob").block().url());
    }

    @Test
    void reRegisteredUsernameCannotReadOldConfigurationAndOldCleanupCannotDeleteNewOne() {
        var oldUser = user("alice", "2026-01-01T00:00:00Z");
        users.put("alice", oldUser);
        var store = store();
        store.save("alice", "https://8.8.8.8/old", null).block();
        users.put("alice", user("alice", "2026-02-01T00:00:00Z"));
        assertNull(store.fetch("alice").block());
        store.save("alice", "https://8.8.8.8/new", null).block();
        store.clear(oldUser).block();
        assertEquals("https://8.8.8.8/new", store.fetch("alice").block().url());
    }

    @Test
    void migratesOldUrlAndTemplatePreservingAlreadySavedConfiguration() {
        users.put("alice", user("alice", "2026-01-01T00:00:00Z"));
        users.put("bob", user("bob", "2026-01-01T00:00:00Z"));
        var store = store();
        legacy = legacy("{\"alice\":{\"url\":\"https://8.8.8.8/a\",\"template\":\"{\\\"text\\\":\\\"{{title}}\\\"}\"},\"bob\":\"https://8.8.8.8/old\",\"deleted\":\"https://8.8.8.8/deleted\"}");
        store.save("bob", "https://8.8.8.8/new", null).block();
        store.migrateLegacy().block();
        assertEquals("{\"text\":\"{{title}}\"}", store.fetch("alice").block().template());
        assertEquals("https://8.8.8.8/new", store.fetch("bob").block().url());
        assertTrue(legacy.getStringData().get(DefaultWebhookConfigStore.LEGACY_DATA_KEY).contains("deleted"));
        store.migrateLegacy().block();
        assertEquals(2, maps.size());
    }

    @Test
    void failedMigrationRetainsLegacyEntryAndCanResume() {
        users.put("alice", user("alice", "2026-01-01T00:00:00Z"));
        var store = store();
        legacy = legacy("{\"alice\":\"https://8.8.8.8/a\"}");
        when(client.create(any(ConfigMap.class))).thenReturn(Mono.error(new IllegalStateException("创建失败")));
        assertThrows(IllegalStateException.class, () -> store.migrateLegacy().block());
        assertTrue(legacy.getStringData().get(DefaultWebhookConfigStore.LEGACY_DATA_KEY).contains("alice"));
        when(client.create(any(ConfigMap.class))).thenAnswer(i -> {
            var input = i.getArgument(0, ConfigMap.class);
            maps.put(input.getMetadata().getName(), input);
            return Mono.just(input);
        });
        store.migrateLegacy().block();
        assertNull(legacy);
    }

    @Test
    void retriesOptimisticConflictAndRejectsInvalidTemplateBeforeWriting() {
        users.put("alice", user("alice", "2026-01-01T00:00:00Z"));
        var store = store();
        store.save("alice", "https://8.8.8.8/a", null).block();
        var first = new AtomicBoolean(true);
        when(client.update(any(ConfigMap.class))).thenAnswer(i ->
            first.getAndSet(false) ? Mono.error(new OptimisticLockingFailureException("冲突"))
                : Mono.just(i.getArgument(0, ConfigMap.class)));
        store.save("alice", null, "{\"text\":\"{{title}}\"}").block();
        assertEquals("{\"text\":\"{{title}}\"}", store.fetch("alice").block().template());
        assertThrows(IllegalArgumentException.class, () -> store.save("alice", null, "{} {}").block());
    }

    static User user(String name, String created) {
        var user = new User();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse(created));
        metadata.setVersion(1L);
        user.setMetadata(metadata);
        return user;
    }

    private static Secret legacy(String value) {
        var secret = new Secret();
        var metadata = new Metadata();
        metadata.setName(DefaultWebhookConfigStore.LEGACY_SECRET_NAME);
        metadata.setVersion(1L);
        secret.setMetadata(metadata);
        secret.setStringData(new HashMap<>(Map.of(DefaultWebhookConfigStore.LEGACY_DATA_KEY, value)));
        return secret;
    }
}
