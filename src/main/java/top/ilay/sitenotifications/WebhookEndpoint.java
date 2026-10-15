package top.ilay.sitenotifications;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.Map;
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
    private final WebhookNotifier notifier;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/userspaces/{username}/webhook", this::get, builder -> describe(builder, "getWebhookConfig", "读取当前用户 Webhook 配置"))
            .PUT("/userspaces/{username}/webhook", this::save, builder -> {
                describe(builder, "saveWebhookConfig", "保存当前用户 Webhook 配置");
                builder.requestBody(requestBodyBuilder().required(true).implementation(WebhookRequest.class));
            })
            .POST("/userspaces/{username}/webhook/-/test", this::test, builder -> {
                builder.operationId("testWebhookConfig").tag(TAG).description("测试当前编辑的 Webhook 配置，不保存")
                    .parameter(parameterBuilder().in(ParameterIn.PATH).name("username").required(true))
                    .response(responseBuilder().implementation(WebhookTestResponse.class));
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
            .map(config -> new WebhookResponse(true, config.url(), config.method(), config.template(), config.headers()))
            .defaultIfEmpty(new WebhookResponse(false, null, "POST", null, Map.of())))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> save(ServerRequest request) {
        return currentUser(request)
            .flatMap(username -> request.bodyToMono(WebhookRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求正文不能为空")))
                .flatMap(body -> WebhookTemplate.validate(body.template())
                    .onErrorMap(IllegalArgumentException.class, error ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()))
                    .then(Mono.defer(() -> configStore.save(username, body.url(), body.method(), body.template(), body.headers())))
                    .then(Mono.defer(() -> configStore.fetch(username)))
                    .map(config -> new WebhookResponse(true, config.url(), config.method(), config.template(), config.headers()))))
            .onErrorMap(IllegalArgumentException.class, error ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> test(ServerRequest request) {
        return currentUser(request).flatMap(username -> request.bodyToMono(WebhookRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求正文不能为空")))
            .flatMap(body -> WebhookTemplate.validate(body.template())
                .then(Mono.fromCallable(() -> new WebhookConfig(body.url(), body.method(), body.template(),
                    body.headers())))
                .flatMap(config -> notifier.sendTest(username, config)
                    .map(status -> new WebhookTestResponse(status >= 200 && status < 300, status,
                        "接收端返回 HTTP " + status))
                    .onErrorResume(error -> Mono.just(new WebhookTestResponse(false, null,
                        "无法完成测试请求，请检查目标服务连接和响应（限时 10 秒）"))))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))))
            .onErrorMap(IllegalArgumentException.class,
                error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()));
    }

    private Mono<ServerResponse> clear(ServerRequest request) {
        return currentUser(request).flatMap(username -> configStore.clear(username))
            .thenReturn(new WebhookResponse(false, null, "POST", null, Map.of()))
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

    public record WebhookRequest(String url, String method, String template, Map<String, String> headers) {
    }

    public record WebhookResponse(boolean configured, String url, String method, String template,
        Map<String, String> headers) {
    }

    public record WebhookTestResponse(boolean success, Integer statusCode, String message) {
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.site-notifications.halo.run", "v1alpha1");
    }
}
