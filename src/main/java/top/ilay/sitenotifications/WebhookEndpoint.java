package top.ilay.sitenotifications;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class WebhookEndpoint implements CustomEndpoint {
    private static final String TAG = "SiteNotificationWebhookV1alpha1Uc";
    private final WebhookConfigStore configStore;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/userspaces/{username}/webhook", this::get, builder -> describe(builder, "getWebhookConfig", "读取当前用户 Webhook 配置"))
            .PUT("/userspaces/{username}/webhook", this::save, builder -> {
                describe(builder, "saveWebhookConfig", "保存当前用户 Webhook 配置");
                builder.requestBody(requestBodyBuilder().required(true).implementation(WebhookRequest.class));
            })
            .DELETE("/userspaces/{username}/webhook", this::clear, builder -> describe(builder, "clearWebhookConfig", "清除当前用户 Webhook 配置"))
            .build();
    }

    private void describe(Builder builder, String operationId, String description) {
        builder.operationId(operationId).tag(TAG).description(description)
            .parameter(parameterBuilder().in(ParameterIn.PATH).name("username").required(true))
            .response(responseBuilder().implementation(WebhookResponse.class));
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        return currentUser(request).flatMap(username -> configStore.fetch(username)
            .map(url -> new WebhookResponse(true, redact(url)))
            .defaultIfEmpty(new WebhookResponse(false, null)))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> save(ServerRequest request) {
        return currentUser(request)
            .flatMap(username -> request.bodyToMono(WebhookRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求正文不能为空")))
                .flatMap(body -> configStore.save(username, body.url())
                    .thenReturn(new WebhookResponse(true, redact(body.url())))))
            .onErrorMap(IllegalArgumentException.class, error ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 地址无效，请使用公网 HTTPS 地址和标准端口"))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> clear(ServerRequest request) {
        return currentUser(request).flatMap(username -> configStore.clear(username))
            .thenReturn(new WebhookResponse(false, null))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<String> currentUser(ServerRequest request) {
        var requested = request.pathVariable("username");
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
            .filter(authentication -> authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .map(authentication -> authentication.getName())
            .filter(requested::equals)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "只能管理当前用户的 Webhook")));
    }

    private static String redact(String value) {
        try {
            var uri = URI.create(value);
            return new URI(uri.getScheme(), uri.getAuthority(), null, null, null).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public record WebhookRequest(String url) {
    }

    public record WebhookResponse(boolean configured, String url) {
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.site-notifications.halo.run", "v1alpha1");
    }
}
