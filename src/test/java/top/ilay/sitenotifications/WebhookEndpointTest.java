package top.ilay.sitenotifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class WebhookEndpointTest {
    private final WebhookConfigStore store = mock(WebhookConfigStore.class);
    private final WebhookNotifier notifier = mock(WebhookNotifier.class);

    private WebTestClient http(String username) {
        var routes = new WebhookEndpoint(store, notifier).endpoint();
        if (username != null) {
            routes = routes.filter((request, next) -> next.handle(request).contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(
                    MessageEndpointTest.authentication(username, "ROLE_authenticated"))));
        }
        return WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void anonymousAndOtherUsersCannotAccessConfiguration() {
        http(null).get().uri("/userspaces/alice/webhook").exchange().expectStatus().isForbidden();
        var bob = http("bob");
        bob.get().uri("/userspaces/alice/webhook").exchange().expectStatus().isForbidden();
        bob.put().uri("/userspaces/alice/webhook").bodyValue("{}").exchange().expectStatus().isForbidden();
        bob.delete().uri("/userspaces/alice/webhook").exchange().expectStatus().isForbidden();
        bob.post().uri("/userspaces/alice/webhook/-/test").bodyValue("{}").exchange().expectStatus().isForbidden();
        http(null).post().uri("/userspaces/alice/webhook/-/test").bodyValue("{}").exchange().expectStatus().isForbidden();
        verifyNoInteractions(store);
        verifyNoInteractions(notifier);
    }

    @Test
    void testsUnsavedConfigurationWithoutWritingToStoreAndReportsStatus() {
        var config = new WebhookConfig("https://8.8.8.8/hook", "PATCH", "{\"text\":\"{{title}}\"}",
            java.util.Map.of("X-Test", "draft"));
        when(notifier.sendTest("alice", config)).thenReturn(Mono.just(202));
        var client = http("alice");
        client.post().uri("/userspaces/alice/webhook/-/test").bodyValue(config).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.statusCode").isEqualTo(202);
        when(notifier.sendTest("alice", config)).thenReturn(Mono.just(503));
        client.post().uri("/userspaces/alice/webhook/-/test").bodyValue(config).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.statusCode").isEqualTo(503);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsInvalidMethodAndHeaderBeforeSendingTest() {
        http("alice").post().uri("/userspaces/alice/webhook/-/test")
            .bodyValue(java.util.Map.of("url", "https://8.8.8.8", "method", "TRACE"))
            .exchange().expectStatus().isBadRequest();
        http("alice").post().uri("/userspaces/alice/webhook/-/test")
            .bodyValue(java.util.Map.of("url", "https://8.8.8.8", "headers", java.util.Map.of("Host", "internal")))
            .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(notifier, store);
    }

    @Test
    void returnsConfiguredUrlAndTemplate() {
        when(store.fetch("alice")).thenReturn(Mono.just(new WebhookConfig(
            "https://example.com/token/secret?key=private", "{\"title\":\"{{title}}\"}")));
        http("alice").get().uri("/userspaces/alice/webhook").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.url").isEqualTo("https://example.com/token/secret?key=private")
            .jsonPath("$.method").isEqualTo("POST")
            .jsonPath("$.template").isEqualTo("{\"title\":\"{{title}}\"}");
    }

    @Test
    void missingConfigurationIsReportedAndClearRemovesOnlyCurrentUser() {
        when(store.fetch("alice")).thenReturn(Mono.empty());
        when(store.clear("alice")).thenReturn(Mono.empty());
        var alice = http("alice");
        alice.get().uri("/userspaces/alice/webhook").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.configured").isEqualTo(false);
        alice.delete().uri("/userspaces/alice/webhook").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.configured").isEqualTo(false);
        verify(store).clear("alice");
    }

    @Test
    void invalidDestinationReturnsBadRequest() {
        when(store.save(eq("alice"), eq("http://example.com"), any(), any(), any()))
            .thenReturn(Mono.error(new IllegalArgumentException("地址无效")));
        when(store.fetch("alice")).thenReturn(Mono.empty());
        http("alice").put().uri("/userspaces/alice/webhook")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"url\":\"http://example.com\"}")
            .exchange().expectStatus().isBadRequest();
    }
}
