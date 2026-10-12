package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;

class MessageEndpointTest {
    ReactiveExtensionClient client;
    MessageService messages;
    MessageEndpoint endpoint;

    @BeforeEach
    void setup() {
        client = mock(ReactiveExtensionClient.class);
        messages = mock(MessageService.class);
        endpoint = new MessageEndpoint(messages, mock(RecipientService.class), new SuperAdminGuard(client));
        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(RecipientServiceTest.user("admin")));
    }

    private WebTestClient http(Authentication authentication) {
        var routes = endpoint.endpoint();
        if (authentication != null) {
            routes = routes.filter((request, next) -> next.handle(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)));
        }
        return WebTestClient.bindToRouterFunction(routes).build();
    }

    static Authentication authentication(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "unused", List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void anonymousAndOrdinaryUsersCannotCancelOrReadTaskSnapshot() {
        http(null).post().uri("/messages/task/cancel").exchange().expectStatus().isForbidden();
        var ordinary = http(authentication("alice", "ROLE_authenticated"));
        ordinary.post().uri("/messages/task/cancel").exchange().expectStatus().isForbidden();
        ordinary.get().uri("/messages/task").exchange().expectStatus().isForbidden();
        verify(messages, never()).cancel(any(), any());
        verify(messages, never()).get(any());
    }

    @Test
    void offsetTimeIsDecodedToUtcAndSenderComesFromAuthentication() {
        when(messages.create(any(), any())).thenReturn(Mono.just(MessageReconcilerTest.message(
            SiteMessage.Phase.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60))));
        http(authentication("admin", "ROLE_super-role")).post().uri("/messages")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""
                {"requestId":"id","title":"标题","content":"正文","type":"MESSAGE",
                 "target":"ALL","sendMode":"SCHEDULED","scheduledAt":"2026-10-09T12:01:00+08:00"}
                """).exchange().expectStatus().isOk();
        var request = ArgumentCaptor.forClass(MessageRequest.class);
        var sender = ArgumentCaptor.forClass(User.class);
        verify(messages).create(request.capture(), sender.capture());
        assertEquals(Instant.parse("2026-10-09T04:01:00Z"), request.getValue().scheduledAt());
        assertEquals("admin", sender.getValue().getMetadata().getName());
    }

    @Test
    void utcTimeStringRemainsAccepted() {
        when(messages.create(any(), any())).thenReturn(Mono.just(MessageReconcilerTest.message(
            SiteMessage.Phase.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60))));
        http(authentication("admin", "ROLE_super-role")).post().uri("/messages")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""
                {"requestId":"id","title":"标题","content":"正文","type":"MESSAGE",
                 "target":"ALL","sendMode":"SCHEDULED","scheduledAt":"2026-10-09T04:01:00Z"}
                """).exchange().expectStatus().isOk();
        var request = ArgumentCaptor.forClass(MessageRequest.class);
        verify(messages).create(request.capture(), any());
        assertEquals(Instant.parse("2026-10-09T04:01:00Z"), request.getValue().scheduledAt());
    }

    @Test
    void numericTimestampTokensAndStringsReturnBadRequestBeforeCreatingTask() {
        // 避免失败测试因未 stub 的命令方法 NPE 而误判为已拒绝数字时间。
        when(messages.create(any(), any())).thenReturn(Mono.just(MessageReconcilerTest.message(
            SiteMessage.Phase.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60))));
        var admin = http(authentication("admin", "ROLE_super-role"));
        for (var value : List.of("1791518460", "1791518460.5", "\"1791518460\"")) {
            var body = """
                {"requestId":"id","title":"标题","content":"正文","type":"MESSAGE",
                 "target":"ALL","sendMode":"SCHEDULED","scheduledAt":%s}
                """.formatted(value);
            admin.post().uri("/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest();
        }
        verify(messages, never()).create(any(), any());
    }

    @Test
    void missingOffsetAndMalformedEnumReturnBadRequestBeforeCreatingTask() {
        var admin = http(authentication("admin", "ROLE_super-role"));
        for (var body : List.of(
            """
                {"requestId":"id","title":"标题","content":"正文","type":"MESSAGE",
                 "target":"ALL","sendMode":"SCHEDULED","scheduledAt":"2026-10-09T12:01:00"}
                """,
            """
                {"requestId":"id","title":"标题","content":"正文","type":"INVALID","target":"ALL"}
                """)) {
            admin.post().uri("/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest();
        }
        verify(messages, never()).create(any(), any());
    }
}
