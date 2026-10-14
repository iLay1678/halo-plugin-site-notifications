package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;

class WebhookUserCleanupTest {
    private final ExtensionClient client = mock(ExtensionClient.class);
    private final WebhookConfigStore configs = mock(WebhookConfigStore.class);
    private final WebhookUserCleanup cleanup = new WebhookUserCleanup(client, configs);

    @Test
    void protectsNewAndExistingUsersAndRemovesOldUsernameMappingBeforeRegistration() {
        var user = user();
        when(configs.clearLegacy("alice")).thenReturn(Mono.empty());
        cleanup.preCreating(user).block();
        assertTrue(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
        verify(configs).clearLegacy("alice");
        user.getMetadata().setFinalizers(new HashSet<>());
        when(client.fetch(User.class, "alice")).thenReturn(Optional.of(user));
        cleanup.reconcile(new Reconciler.Request("alice"));
        verify(client).update(user);
        assertTrue(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
    }

    @Test
    void waitsForActualResourceRemovalBeforeReleasingUserDeletion() {
        var user = user();
        user.getMetadata().setDeletionTimestamp(Instant.now());
        var map = new ConfigMap();
        map.setMetadata(new Metadata());
        map.getMetadata().setName(DefaultWebhookConfigStore.configMapName(user));
        when(client.fetch(User.class, "alice")).thenReturn(Optional.of(user));
        when(client.fetch(ConfigMap.class, map.getMetadata().getName())).thenReturn(Optional.of(map));
        cleanup.reconcile(new Reconciler.Request("alice"));
        verify(client).delete(map);
        assertTrue(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
        map.getMetadata().setDeletionTimestamp(Instant.now());
        cleanup.reconcile(new Reconciler.Request("alice"));
        assertTrue(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
        when(client.fetch(ConfigMap.class, map.getMetadata().getName())).thenReturn(Optional.empty());
        when(configs.clearLegacy("alice")).thenReturn(Mono.empty());
        cleanup.reconcile(new Reconciler.Request("alice"));
        assertFalse(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
        assertTrue(user.getMetadata().getFinalizers().contains("other-plugin/protection"));
    }

    @Test
    void stopReleasesOnlyThisPluginsFinalizer() {
        var user = user();
        when(client.listAllNames(eq(User.class), any(ListOptions.class), any()))
            .thenReturn(java.util.List.of("alice"));
        when(client.fetch(User.class, "alice")).thenReturn(Optional.of(user));
        cleanup.releaseFinalizers();
        assertFalse(user.getMetadata().getFinalizers().contains(WebhookUserCleanup.FINALIZER));
        assertTrue(user.getMetadata().getFinalizers().contains("other-plugin/protection"));
        verify(client).update(user);
    }

    private static User user() {
        var user = DefaultWebhookConfigStoreTest.user("alice", "2026-01-01T00:00:00Z");
        user.getMetadata().setFinalizers(new HashSet<>(Set.of(WebhookUserCleanup.FINALIZER,
            "other-plugin/protection")));
        return user;
    }
}
