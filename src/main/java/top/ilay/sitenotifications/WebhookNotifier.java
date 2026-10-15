package top.ilay.sitenotifications;

import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import run.halo.app.notification.NotificationContext;
import run.halo.app.notification.ReactiveNotifier;

/** 将 Halo 通知投递到收件用户的 Webhook。 */
@Slf4j
@Component
public class WebhookNotifier implements ReactiveNotifier {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final WebhookConfigStore configStore;
    private final WebClient webClient;
    private final WebhookAddressResolverGroup resolverGroup;

    @Autowired
    public WebhookNotifier(WebhookConfigStore configStore) {
        this(configStore, WebClient.builder());
    }

    WebhookNotifier(WebhookConfigStore configStore, WebClient.Builder builder) {
        this.configStore = configStore;
        this.resolverGroup = new WebhookAddressResolverGroup(HttpResources.get().getOrCreateDefaultResolver());
        this.webClient = webClient(builder, resolverGroup);
    }

    static WebClient webClient(WebClient.Builder builder, AddressResolverGroup<?> resolver) {
        return builder.clone()
            .clientConnector(new ReactorClientHttpConnector(httpClient(resolver)))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024))
            .build();
    }

    static HttpClient httpClient(AddressResolverGroup<?> resolver) {
        return HttpClient.create()
            .resolver(resolver)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .followRedirect(false)
            .responseTimeout(REQUEST_TIMEOUT);
    }

    @Override
    public Mono<Void> notify(NotificationContext context) {
        if (context == null || context.getMessage() == null
            || context.getMessage().getRecipient() == null) {
            return Mono.empty();
        }
        var username = context.getMessage().getRecipient();
        return Mono.defer(() -> configStore.fetch(username))
            .flatMap(config -> WebhookUrlValidator.validate(config.url())
                .zipWith(Mono.fromCallable(() -> WebhookTemplate.render(context, config.template())))
                .flatMap(result -> send(config, result.getT1(), result.getT2()))
                .flatMap(status -> status >= 200 && status < 300 ? Mono.<Void>empty()
                    : Mono.error(new IllegalStateException("Webhook 接收端返回非成功状态"))))
            .timeout(REQUEST_TIMEOUT)
            .onErrorResume(error -> {
                log.warn("Webhook notification skipped for user [{}] ({})", username,
                    error.getClass().getSimpleName());
                return Mono.empty();
            });
    }

    Mono<Integer> send(WebhookConfig config, URI uri, String body) {
        var request = webClient.method(HttpMethod.valueOf(config.method())).uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> config.headers().forEach(headers::set));
        WebClient.RequestHeadersSpec<?> outgoing = Set.of("GET", "DELETE").contains(config.method())
            ? request : request.bodyValue(body);
        return outgoing.exchangeToMono(response -> response.bodyToMono(byte[].class)
            .thenReturn(response.statusCode().value()));
    }

    Mono<Integer> sendTest(String username, WebhookConfig config) {
        var context = new NotificationContext();
        var payload = new NotificationContext.MessagePayload();
        payload.setTitle("Webhook 测试消息");
        payload.setRawBody("这是一条测试消息。");
        payload.setHtmlBody("<p>这是一条测试消息。</p>");
        var attributes = new run.halo.app.notification.ReasonAttributes();
        attributes.put("title", "Webhook 测试消息");
        attributes.put("content", "这是一条测试消息。");
        payload.setAttributes(attributes);
        var message = new NotificationContext.Message();
        message.setRecipient(username);
        message.setTimestamp(Instant.now());
        var subject = NotificationContext.Subject.builder().apiVersion("v1alpha1")
            .kind("WebhookTest").name("webhook-test").title("Webhook 测试消息")
            .url("/uc/notifications").build();
        message.setSubject(subject);
        message.setPayload(payload);
        context.setMessage(message);
        return WebhookUrlValidator.validate(config.url())
            .flatMap(uri -> send(config, uri, WebhookTemplate.render(context, config.template())))
            .timeout(REQUEST_TIMEOUT);
    }

    @PreDestroy
    public void close() {
        resolverGroup.close();
    }
}
