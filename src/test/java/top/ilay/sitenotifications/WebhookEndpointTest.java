package top.ilay.sitenotifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class WebhookEndpointTest {
    private final WebhookConfigStore store = mock(WebhookConfigStore.class);

    private WebTestClient http(String username) {
        var routes = new WebhookEndpoint(store).endpoint();
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
        verifyNoInteractions(store);
    }

    @Test
    void hidesCredentialsInPathQueryAndFragmentFromResponse() {
        when(store.fetch("alice")).thenReturn(Mono.just("https://example.com/token/secret?key=private"));
        http("alice").get().uri("/userspaces/alice/webhook").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.url").isEqualTo("https://example.com");
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
        when(store.save("alice", "http://example.com"))
            .thenReturn(Mono.error(new IllegalArgumentException("地址无效")));
        http("alice").put().uri("/userspaces/alice/webhook")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"url\":\"http://example.com\"}")
            .exchange().expectStatus().isBadRequest();
    }
}
