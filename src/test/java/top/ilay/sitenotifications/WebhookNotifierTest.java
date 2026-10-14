package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.resolver.NoopAddressResolverGroup;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.notification.NotificationContext;

class WebhookNotifierTest {
    private final WebhookConfigStore store = mock(WebhookConfigStore.class);

    @Test
    void missingConfigurationSkipsSending() {
        when(store.fetch("alice")).thenReturn(Mono.empty());
        var requests = new AtomicInteger();
        var notifier = notifier(request -> {
            requests.incrementAndGet();
            return Mono.error(new AssertionError("未配置地址时不应发送"));
        });
        StepVerifier.create(notifier.notify(context())).verifyComplete();
        assertEquals(0, requests.get());
        notifier.close();
    }

    @Test
    void sendsJsonPostToTheRecipientsConfiguredDestination() {
        var destination = URI.create("https://8.8.8.8/hook?token=secret");
        when(store.fetch("alice")).thenReturn(Mono.just(destination.toString()));
        var capturedBody = new AtomicReference<String>();
        var capturedError = new AtomicReference<Throwable>();
        var notifier = notifier(request -> {
            assertEquals(HttpMethod.POST, request.method());
            assertEquals(destination, request.url());
            assertEquals("application/json", request.headers().getContentType().toString());
            var output = new MockClientHttpRequest(request.method(), request.url());
            return request.body().insert(output, new BodyInserter.Context() {
                public java.util.List<HttpMessageWriter<?>> messageWriters() {
                    return HandlerStrategies.withDefaults().messageWriters();
                }

                public Optional<ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }

                public Map<String, Object> hints() {
                    return Map.of();
                }
            }).then(Mono.defer(output::getBodyAsString)).map(body -> {
                capturedBody.set(body);
                return ClientResponse.create(HttpStatus.NO_CONTENT).build();
            }).doOnError(capturedError::set);
        });
        StepVerifier.create(notifier.notify(context())).verifyComplete();
        assertNull(capturedError.get(), () -> String.valueOf(capturedError.get()));
        var json = JsonUtils.jsonToObject(capturedBody.get(), Map.class);
        assertEquals("1", json.get("version"));
        assertEquals("系统消息", json.get("title"));
        assertEquals("正文", json.get("rawBody"));
        assertEquals("alice", json.get("recipient"));
        notifier.close();
    }

    @Test
    void configurationAndDeliveryErrorsAreIsolated() {
        when(store.fetch("alice")).thenReturn(Mono.error(new IllegalStateException("Secret 读取失败")));
        var requests = new AtomicInteger();
        var notifier = notifier(request -> {
            requests.incrementAndGet();
            return Mono.error(new IllegalStateException("发送失败"));
        });
        StepVerifier.create(notifier.notify(context())).verifyComplete();
        assertEquals(0, requests.get());
        when(store.fetch("alice")).thenReturn(Mono.just("https://8.8.8.8/hook"));
        StepVerifier.create(notifier.notify(context())).verifyComplete();
        assertEquals(1, requests.get());
        notifier.close();
    }

    @Test
    void responseErrorsAndTimeoutAreIsolated() {
        when(store.fetch("alice")).thenReturn(Mono.just("https://8.8.8.8/hook"));
        var notifier = notifier(request -> Mono.error(new DataBufferLimitException("响应过大")));
        StepVerifier.create(notifier.notify(context())).verifyComplete();
        notifier.close();
        var stalled = notifier(request -> Mono.never());
        StepVerifier.withVirtualTime(() -> stalled.notify(context()))
            .thenAwait(WebhookNotifier.REQUEST_TIMEOUT.plusSeconds(1))
            .verifyComplete();
        stalled.close();
    }

    @Test
    void httpResponseBodyIsLimitedTo64KiB() {
        var server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> response.sendString(Mono.just("x".repeat(70 * 1024))))
            .bindNow();
        try {
            var client = WebhookNotifier.webClient(WebClient.builder(), NoopAddressResolverGroup.INSTANCE);
            StepVerifier.create(client.get().uri("http://127.0.0.1:" + server.port())
                .exchangeToMono(response -> response.bodyToMono(byte[].class)))
                .expectError(DataBufferLimitException.class).verify(Duration.ofSeconds(5));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void httpTransportDoesNotFollowRedirects() {
        var redirected = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
            .get("/redirect", (request, response) -> response.status(302)
                .header("Location", "/target").send())
            .get("/target", (request, response) -> {
                redirected.incrementAndGet();
                return response.send();
            })).bindNow();
        try {
            var status = WebhookNotifier.httpClient(NoopAddressResolverGroup.INSTANCE).get()
                .uri("http://127.0.0.1:" + server.port() + "/redirect")
                .responseSingle((response, body) -> body.thenReturn(response.status().code()))
                .block(Duration.ofSeconds(5));
            assertEquals(302, status);
            assertEquals(0, redirected.get());
        } finally {
            server.disposeNow();
        }
    }

    private WebhookNotifier notifier(ExchangeFunction exchange) {
        return new WebhookNotifier(store, WebClient.builder().exchangeFunction(exchange));
    }

    static NotificationContext context() {
        var payload = new NotificationContext.MessagePayload();
        payload.setTitle("系统消息");
        payload.setRawBody("正文");
        payload.setHtmlBody("<p>正文</p>");
        var message = new NotificationContext.Message();
        message.setRecipient("alice");
        message.setTimestamp(Instant.parse("2026-10-14T00:00:00Z"));
        message.setPayload(payload);
        var context = new NotificationContext();
        context.setMessage(message);
        return context;
    }
}
