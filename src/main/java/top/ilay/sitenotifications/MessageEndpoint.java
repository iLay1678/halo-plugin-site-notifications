package top.ilay.sitenotifications;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import top.ilay.sitenotifications.MessageService.SiteMessagePage;
import top.ilay.sitenotifications.RecipientService.RecipientPreview;
import top.ilay.sitenotifications.RecipientService.RoleCandidateList;
import top.ilay.sitenotifications.RecipientService.UserCandidateList;

@Component
@RequiredArgsConstructor
public class MessageEndpoint implements CustomEndpoint {
    private static final String TAG = "SiteMessageV1alpha1Console";
    private final MessageService messages;
    private final RecipientService recipients;
    private final SuperAdminGuard guard;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/messages", this::list, builder -> builder.operationId("listSiteMessages").tag(TAG)
                .description("分页查询站内通知发送记录，仅超级管理员可用")
                .parameter(parameterBuilder().in(ParameterIn.QUERY).name("page").implementation(Integer.class)
                    .description("页码，从 1 开始，默认 1"))
                .parameter(parameterBuilder().in(ParameterIn.QUERY).name("size").implementation(Integer.class)
                    .description("每页数量，1–100，默认 20"))
                .response(responseBuilder().implementation(SiteMessagePage.class)))
            .GET("/messages/{name}", this::get, builder -> named(builder, "getSiteMessage", "查询发送详情"))
            .POST("/messages", this::create, builder -> builder.operationId("createSiteMessage").tag(TAG)
                .description("冻结接收名单并创建立即或定时发送任务")
                .requestBody(requestBodyBuilder().required(true).implementation(MessageRequest.class))
                .response(responseBuilder().implementation(SiteMessage.class)))
            .POST("/messages/{name}/cancel", this::cancel,
                builder -> named(builder, "cancelSiteMessage", "取消尚未开始投递的定时任务"))
            .POST("/messages/{name}/retry", this::retry,
                builder -> named(builder, "retrySiteMessage", "重试失败接收人，保留已经投递的记录"))
            .POST("/recipients/preview", this::preview, builder -> builder.operationId("previewRecipients").tag(TAG)
                .description("预览当前接收人数，实际名单以提交发送任务时为准")
                .requestBody(requestBodyBuilder().required(true).implementation(RecipientRequest.class))
                .response(responseBuilder().implementation(RecipientPreview.class)))
            .GET("/recipients/users", this::users,
                builder -> candidates(builder, "listRecipientUsers", UserCandidateList.class))
            .GET("/recipients/roles", this::roles,
                builder -> candidates(builder, "listRecipientRoles", RoleCandidateList.class))
            .build();
    }

    private void named(Builder builder, String operationId, String description) {
        builder.operationId(operationId).tag(TAG).description(description)
            .parameter(parameterBuilder().in(ParameterIn.PATH).name("name").required(true))
            .response(responseBuilder().implementation(SiteMessage.class));
    }

    private void candidates(Builder builder, String operationId, Class<?> responseType) {
        builder.operationId(operationId).tag(TAG).description("搜索可接收消息的对象，最多返回 100 条")
            .parameter(parameterBuilder().in(ParameterIn.QUERY).name("keyword").implementation(String.class))
            .response(responseBuilder().implementation(responseType));
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> messages.list(
                number(request, "page", 1), number(request, "size", 20)))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> messages.get(request.pathVariable("name")))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> create(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> request.bodyToMono(MessageRequest.class)
                .switchIfEmpty(Mono.error(MessageRequest.badRequest("请求正文不能为空")))
                .flatMap(input -> messages.create(input, admin)))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> cancel(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> messages.cancel(request.pathVariable("name"), admin.getMetadata().getName()))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> retry(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> messages.retry(request.pathVariable("name")))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> preview(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> request.bodyToMono(RecipientRequest.class)
                .switchIfEmpty(Mono.error(MessageRequest.badRequest("请求正文不能为空")))
                .flatMap(recipients::resolve))
            .flatMap(names -> ServerResponse.ok().bodyValue(new RecipientPreview(names.size())));
    }

    private Mono<ServerResponse> users(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> recipients.users(request.queryParam("keyword").orElse("")))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private Mono<ServerResponse> roles(ServerRequest request) {
        return guard.currentAdmin().flatMap(admin -> recipients.roles(request.queryParam("keyword").orElse("")))
            .flatMap(value -> ServerResponse.ok().bodyValue(value));
    }

    private static int number(ServerRequest request, String name, int fallback) {
        try {
            return Integer.parseInt(request.queryParam(name).orElse(Integer.toString(fallback)));
        } catch (NumberFormatException error) {
            throw MessageRequest.badRequest(name + " 必须是整数");
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.site-notifications.halo.run", "v1alpha1");
    }
}
