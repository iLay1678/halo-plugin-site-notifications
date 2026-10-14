package top.ilay.sitenotifications;

import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
            .flatMap(WebhookUrlValidator::validate)
            .flatMap(uri -> send(uri, WebhookPayload.from(context)))
            .timeout(REQUEST_TIMEOUT)
            .onErrorResume(error -> {
                log.warn("Webhook notification skipped for user [{}] ({})", username,
                    error.getClass().getSimpleName());
                return Mono.empty();
            });
    }

    private Mono<Void> send(URI uri, WebhookPayload payload) {
        return webClient.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .onStatus(status -> !status.is2xxSuccessful(), response ->
                Mono.error(new IllegalStateException("Webhook 接收端返回非成功状态")))
            .bodyToMono(byte[].class)
            .then();
    }

    @PreDestroy
    public void close() {
        resolverGroup.close();
    }
}
